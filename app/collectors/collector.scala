package collectors

import utils.{LifecycleWithoutApp, Logging, ScheduledAgent}
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.agent.Agent
import org.joda.time.DateTime
import akka.actor.ActorSystem

class CollectorAgent[T](val collectors:Seq[Collector[T]], lazyStartup:Boolean = true) extends Logging with LifecycleWithoutApp {

  private var datumAgents:Map[Collector[T], ScheduledAgent[Datum[T]]] = Map.empty

  def get(collector: Collector[T]): Datum[T] = datumAgents(collector)()

  def get(): Iterable[Datum[T]] = datumAgents.values.map(_())
  
  def getLabels: Seq[Label] = get().map(_.label).toSeq

  def update(collector: Collector[T], previous:Datum[T]):Datum[T] = {
      val datum = Datum[T](collector)
      CollectorAgent.update(datum.label)
      datum.label match {
        case l@Label(product, origin, _, None) =>
          log.info(s"Crawl of ${product.name} from $origin successful: ${datum.data.size} records, ${l.bestBefore}")
          datum
        case Label(product, origin, _, Some(error)) =>
          previous.label match {
            case bad if bad.isError =>
              log.error(s"Crawl of ${product.name} from $origin failed: NO data available as this has not been crawled successfuly since Prism started", error)
            case stale if stale.bestBefore.isStale =>
              log.error(s"Crawl of ${product.name} from $origin failed: leaving previously crawled STALE data (${stale.bestBefore.age.getStandardSeconds} seconds old)", error)
            case notYetStale if !notYetStale.bestBefore.isStale =>
              log.warn(s"Crawl of ${product.name} from $origin failed: leaving previously crawled data (${notYetStale.bestBefore.age.getStandardSeconds} seconds old)", error)
          }
          previous
      }
  }

  def init() {
    datumAgents = collectors.map { collector =>
      val initial = if (lazyStartup) {
        val startupData = Datum.empty[T](collector)
        CollectorAgent.update(startupData.label)
        startupData
      } else {
        val startupData = update(collector, Datum.empty[T](collector))
        assert(!startupData.label.isError, s"Error occured collecting data when lazy startup is disabled: ${startupData.label}")
        startupData
      }

      val agent = ScheduledAgent[Datum[T]](0 seconds, 60 seconds, initial){ previous =>
        update(collector, previous)
      }
      collector -> agent
    }.toMap
  }

  def shutdown() {
    datumAgents.values.foreach(_.shutdown())
    datumAgents = Map.empty
  }
}

case class SourceStatus(state: Label, error: Option[Label] = None) {
  lazy val latest = error.getOrElse(state)
}

object CollectorAgent {
  implicit val actorSystem = ActorSystem("collector-agent")
  val labelAgent = Agent[Map[(Resource, Origin),SourceStatus]](Map.empty)

  def update(label:Label) {
    labelAgent.send { previousMap =>
      val key = (label.resource, label.origin)
      val previous = previousMap.get(key)
      val next = label match {
        case good if !good.isError => SourceStatus(good)
        case bad => SourceStatus(previous.map(_.state).getOrElse(bad), Some(bad))
      }
      previousMap + (key -> next)
    }
  }

  def sources:Datum[SourceStatus] = {
    val statusList = labelAgent().values
    val statusDates = statusList.map(_.latest.createdAt)
    val oldestDate = statusDates.toList.sortBy(_.getMillis).headOption.getOrElse(new DateTime(0))
    val label = Label(
      Resource("sources", org.joda.time.Duration.standardMinutes(5L)),
      new Origin {
        def vendor: String = "prism"
        def account: String = "prism"
      },
      oldestDate
    )
    Datum(label, statusList.toSeq)
  }
}