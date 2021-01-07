package agent

import akka.actor.ActorSystem
import akka.agent.Agent
import net.logstash.logback.marker.Markers
import org.joda.time.DateTime
import utils._

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

class CollectorAgent[T<:IndexedItem](val collectorSet: CollectorSet[T], sourceStatusAgent: SourceStatusAgent, lazyStartup:Boolean = true)(actorSystem: ActorSystem) extends CollectorAgentTrait[T] with Logging with Marker with LifecycleWithoutApp {

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

  override def toMarkerMap: Map[String, Any] = Map("records" -> size, "durationType" -> "crawl")

  def update(collector: Collector[T], previous:Datum[T]):Datum[T] = {
    val s = new StopWatch
    val datum = Datum[T](collector)
    val timeSpent = s.elapsed
    sourceStatusAgent.update(datum.label)
    datum.label match {
      case l@Label(product, origin, size, _, None) =>
        val marker = Markers.appendEntries((
          origin.toMarkerMap ++ l.toMarkerMap ++ this.toMarkerMap ++ Map("duration" -> timeSpent)).asJava)
        log.info(s"Crawl of ${product.name} from $origin successful (${timeSpent}ms): $size records, ${l.bestBefore}")(marker)
        datum
      case l@Label(product, origin, _, _, Some(error)) =>
        val marker = Markers.appendEntries((l.toMarkerMap ++ this.toMarkerMap ++ Map("duration" -> timeSpent)).asJava)
        previous.label match {
          case bad if bad.isError =>
            log.error(s"Crawl of ${product.name} from $origin failed (${timeSpent}ms): NO data available as this has not been crawled successfuly since Prism started", error)(marker)
          case stale if stale.bestBefore.isStale =>
            log.error(s"Crawl of ${product.name} from $origin failed (${timeSpent}ms): leaving previously crawled STALE data (${stale.bestBefore.age.getStandardSeconds} seconds old)", error)(marker)
          case notYetStale if !notYetStale.bestBefore.isStale =>
            log.warn(s"Crawl of ${product.name} from $origin failed (${timeSpent}ms): leaving previously crawled data (${notYetStale.bestBefore.age.getStandardSeconds} seconds old)", error)(marker)
        }
        previous
    }
  }

  def init():Unit = {
    log.info(s"Starting agent for collectors: $collectors")

    datumAgents = collectors.flatMap { collector =>
      val initial = if (lazyStartup) {
        val startupData = Datum.empty[T](collector)
        sourceStatusAgent.update(startupData.label)
        startupData
      } else {
        val startupData = update(collector, Datum.empty[T](collector))
        assert(!startupData.label.isError, s"Error occurred collecting data when lazy startup is disabled: ${startupData.label}")
        startupData
      }

      val randomDelay = new Random().nextInt(60)

      val agent: Option[ScheduledAgent[Datum[T]]] = Some(collector.crawlRate.refreshPeriod).collect {
        case fd: FiniteDuration =>
          ScheduledAgent[Datum[T]](randomDelay seconds, fd, initial){ previous =>
            update(collector, previous)
          }
      }
      if (agent.isEmpty) {
        log.warn(s"The crawl rate period for $collector is ${collector.crawlRate.refreshPeriod}. This is not a finite duration so we are not initialising an agent.")
      }
      agent.map(collector -> _)
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

class SourceStatusAgent(actorSystem: ActorSystem, prismRunTimeStopWatch: StopWatch) extends Logging with Marker {
  implicit private val collectorAgent: ExecutionContext = actorSystem.dispatchers.lookup("collectorAgent")
  val sourceStatusAgent: Agent[Map[(ResourceType, Origin), SourceStatus]] = Agent(Map.empty)

  val initialisedResources: mutable.Map[ResourceType, Boolean] = mutable.Map()

  def update(label:Label):Unit = {
    sourceStatusAgent.alter { previousMap =>
      val key = (label.resourceType, label.origin)
      val previous = previousMap.get(key)
      val next = label match {
        case good if !good.isError => SourceStatus(good)
        case bad => SourceStatus(previous.map(_.state).getOrElse(bad), Some(bad))
      }
      previousMap + (key -> next)
    } onComplete {
      case Success(newMap) =>
        if (!initialisedResources.getOrElse(label.resourceType, false)) {
          val timeSpent = prismRunTimeStopWatch.elapsed
          val uninitialisedSources = newMap.values.count(_.state.status != "success")
          val marker = Markers.appendEntries(Map(
            "totalSourcesToCrawl" -> newMap.size,
            "resource" -> label.resourceType.name,
            "sourcesYetToCrawl" -> uninitialisedSources,
            "duration" -> timeSpent,
            "durationType" -> "healthcheck",
            "percentageCrawled" -> math.floor((newMap.size - uninitialisedSources)/ newMap.size.toFloat)
          ).asJava)
          if (uninitialisedSources == 0) {
            initialisedResources += (label.resourceType -> true)
            log.info(s"Healthcheck passed successfully for ${label.resourceType.name} after ${timeSpent}ms")(marker)
          } else {
            log.info(s"$uninitialisedSources out of ${newMap.size} still not healthy after ${timeSpent}ms")(marker)
          }
        }
      case Failure(_) => log.warn(s"failed to update resource ${label.resourceType.name}")
    }
  }

  val bootTime = new DateTime()

  def sources:Datum[SourceStatus] = {
    val statusList = sourceStatusAgent().values

    val label = Label(
      ResourceType("sources"),
      new Origin {
        val vendor = "prism"
        val account = "prism"
        val resources = Set("sources")
        val crawlRate = Map(("sources" -> CrawlRate(Duration.Inf, Duration.Undefined)))
        val jsonFields = Map.empty[String, String]

        override def toMarkerMap: Map[String, Any] = jsonFields
      },
      statusList.size,
      bootTime
    )
    Datum(label, statusList.toSeq)
  }
  override def toMarkerMap: Map[String, Any] = Map.empty
}