package collectors

import utils.{LifecycleWithoutApp, Logging, ScheduledAgent}
import scala.concurrent.duration._
import scala.language.postfixOps

class CollectorAgent[T](val collectors:Seq[Collector[T]]) extends Logging with LifecycleWithoutApp {

  private var datumAgents:Map[Collector[T], ScheduledAgent[Datum[T]]] = Map.empty

  def get(collector: Collector[T]): Datum[T] = datumAgents(collector)()

  def get(): Iterable[Datum[T]] = datumAgents.values.map(_())

  def init() {
    datumAgents = collectors.map { collector =>
      val agent = ScheduledAgent[Datum[T]](0 seconds, 60 seconds, Datum.empty[T](collector)) { previous =>
        val datum = Datum[T](collector)
        datum.label match {
          case GoodLabel(product, origin, bb) =>
            log.info(s"Crawl of ${product.name} from $origin successful: ${datum.data.size} records, $bb")
            datum
          case BadLabel(product, origin, error) =>
            previous.label match {
              case GoodLabel(_,_,bb) if bb.isStale =>
                log.error(s"Crawl of ${product.name} from $origin failed: leaving previously crawled STALE data (${bb.age.getStandardSeconds} seconds old)", error)
              case GoodLabel(_,_,bb) if !bb.isStale =>
                log.warn(s"Crawl of ${product.name} from $origin failed: leaving previously crawled data (${bb.age.getStandardSeconds} seconds old)", error)
              case BadLabel(_,_,_) =>
                log.error(s"Crawl of ${product.name} from $origin failed: NO data available as this has not been crawled successfuly since Prism started", error)
            }
            previous
        }
      }
      collector -> agent
    }.toMap
  }

  def shutdown() {
    datumAgents.values.foreach(_.shutdown())
    datumAgents = Map.empty
  }
}