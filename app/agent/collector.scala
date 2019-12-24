package agent

import play.api.libs.concurrent.Akka
import utils.{LifecycleWithoutApp, Logging, ScheduledAgent}
import scala.concurrent.duration._
import scala.language.postfixOps
import akka.agent.Agent
import org.joda.time.DateTime
import akka.actor.ActorSystem
import play.api.Play.current
import scala.concurrent.ExecutionContext
import play.api.libs.json.Json.JsValueWrapper
import conf.SourceMetrics
import com.gu.management.StopWatch

class CollectorAgent[T<:IndexedItem](val collectorSet: CollectorSet[T], lazyStartup:Boolean = true) extends Logging with LifecycleWithoutApp {

  implicit private val collectorAgent: ExecutionContext = Akka.system.dispatchers.lookup("collectorAgent")
  val collectors = collectorSet.collectors

  val resourceName = collectors.headOption.map(_.resource.name)

  private var datumAgents:Map[Collector[T], ScheduledAgent[Datum[T]]] = Map.empty

  def get(collector: Collector[T]): Datum[T] = datumAgents(collector)()

  def get(): Iterable[Datum[T]] = datumAgents.values.map(_())

  def getTuples: Iterable[(Label, T)] = get().flatMap(datum => datum.data.map(datum.label ->))
  
  def getLabels: Seq[Label] = get().map(_.label).toSeq

  def size = get().map(_.data.size).sum

  def update(collector: Collector[T], previous:Datum[T]):Datum[T] = {
      val s = new StopWatch
      val datum = Datum[T](collector)
      val timeSpent = s.elapsed
      SourceMetrics.CrawlTimer.recordTimeSpent(timeSpent)
      CollectorAgent.update(datum.label)
      datum.label match {
        case l@Label(product, origin, size, _, None) =>
          log.info(s"Crawl of ${product.name} from $origin successful (${timeSpent}ms): $size records, ${l.bestBefore}")
          SourceMetrics.CrawlSuccessCounter.increment()
          datum
        case Label(product, origin, _, _, Some(error)) =>
          previous.label match {
            case bad if bad.isError =>
              log.error(s"Crawl of ${product.name} from $origin failed (${timeSpent}ms): NO data available as this has not been crawled successfuly since Prism started", error)
            case stale if stale.bestBefore.isStale =>
              log.error(s"Crawl of ${product.name} from $origin failed (${timeSpent}ms): leaving previously crawled STALE data (${stale.bestBefore.age.getStandardSeconds} seconds old)", error)
            case notYetStale if !notYetStale.bestBefore.isStale =>
              log.warn(s"Crawl of ${product.name} from $origin failed (${timeSpent}ms): leaving previously crawled data (${notYetStale.bestBefore.age.getStandardSeconds} seconds old)", error)
          }
          SourceMetrics.CrawlFailureCounter.increment()
          previous
      }
  }

  def init() {
    log.info(s"Starting agent for collectors: $collectors")

    datumAgents = collectors.map { collector =>
      val initial = if (lazyStartup) {
        val startupData = Datum.empty[T](collector)
        CollectorAgent.update(startupData.label)
        startupData
      } else {
        val startupData = update(collector, Datum.empty[T](collector))
        assert(!startupData.label.isError, s"Error occurred collecting data when lazy startup is disabled: ${startupData.label}")
        startupData
      }

      val agent = ScheduledAgent[Datum[T]](0 seconds, collectorSet.resource.refreshPeriod, initial){ previous =>
        update(collector, previous)
      }
      collector -> agent
    }.toMap

    log.info(s"Started agent for collectors: $collectors")
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
  implicit private val collectorAgent: ExecutionContext = Akka.system.dispatchers.lookup("collectorAgent")
  val labelAgent = Agent[Map[(ResourceType, Origin),SourceStatus]](Map.empty)

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
    val smallestDuration = statusList.map(_.latest.resource.shelfLife).minBy(_.toSeconds)
    val label = Label(
      ResourceType("sources", smallestDuration, smallestDuration),
      new Origin {
        val vendor = "prism"
        val account = "prism"
        val resources = Set("sources")
        val jsonFields = Map.empty[String, String]
      },
      statusList.size,
      oldestDate
    )
    Datum(label, statusList.toSeq)
  }
}