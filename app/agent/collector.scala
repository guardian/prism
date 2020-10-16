package agent

import utils.{LifecycleWithoutApp, Logging, ScheduledAgent, StopWatch}

import scala.concurrent.duration._
import scala.language.postfixOps
import akka.agent.Agent
import org.joda.time.DateTime
import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext

class CollectorAgent[T<:IndexedItem](val collectorSet: CollectorSet[T], sourceStatusAgent: SourceStatusAgent, lazyStartup:Boolean = true)(actorSystem: ActorSystem) extends CollectorAgentTrait[T] with Logging with LifecycleWithoutApp {

  implicit private val collectorAgent: ExecutionContext = actorSystem.dispatchers.lookup("collectorAgent")
  val collectors: Seq[Collector[T]] = collectorSet.collectors

  val resourceName: Option[String] = collectors.headOption.map(_.resource.name)

  private var datumAgents:Map[Collector[T], ScheduledAgent[Datum[T]]] = Map.empty

  log.info(s"new collector set - ${collectorSet.resource.name} - with collectors ${collectors}")

  def get(collector: Collector[T]): Datum[T] = datumAgents(collector)()

  def get(): Iterable[Datum[T]] = datumAgents.values.map(_())

  def getTuples: Iterable[(Label, T)] = get().flatMap(datum => datum.data.map(datum.label ->))

  def getLabels: Seq[Label] = get().map(_.label).toSeq

  def size: Int = get().map(_.data.size).sum

  def update(collector: Collector[T], previous:Datum[T]):Datum[T] = {
    val s = new StopWatch
    val datum = Datum[T](collector)
    val timeSpent = s.elapsed
    sourceStatusAgent.update(datum.label)
    datum.label match {
      case l@Label(product, origin, size, _, None) =>
        log.info(s"Crawl of ${product.name} from $origin successful (${timeSpent}ms): $size records, ${l.bestBefore}")
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
        previous
    }
  }

  def init():Unit = {
    log.info(s"Starting agent for collectors: $collectors")

    datumAgents = collectors.map { collector =>
      val initial = if (lazyStartup) {
        val startupData = Datum.empty[T](collector)
        sourceStatusAgent.update(startupData.label)
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

  def shutdown():Unit = {
    datumAgents.values.foreach(_.shutdown())
    datumAgents = Map.empty
  }
}

trait CollectorAgentTrait[T<:IndexedItem] {
  def get(collector: Collector[T]): Datum[T]

  def get(): Iterable[Datum[T]]

  def getTuples: Iterable[(Label, T)]

  def getLabels: Seq[Label]

  def size: Int

  def update(collector: Collector[T], previous:Datum[T]):Datum[T]

  def init():Unit

  def shutdown():Unit
}

case class SourceStatus(state: Label, error: Option[Label] = None) {
  lazy val latest: Label = error.getOrElse(state)
}

class SourceStatusAgent(actorSystem: ActorSystem) {
  implicit private val collectorAgent: ExecutionContext = actorSystem.dispatchers.lookup("collectorAgent")
  val sourceStatusAgent: Agent[Map[(ResourceType, Origin), SourceStatus]] = Agent(Map.empty)

  def update(label:Label):Unit = {
    sourceStatusAgent.send { previousMap =>
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
    val statusList = sourceStatusAgent().values
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