package collectors

import utils.{Logging, ScheduledAgent}
import scala.concurrent.duration._
import scala.language.postfixOps

abstract class AggregateCollector[T](val collectors:Seq[Collector[T]]) extends Store[T] with Logging {

  val datumAgents:Map[Collector[T], ScheduledAgent[Datum[T]]] = collectors.map { collector =>
    val agent = ScheduledAgent[Datum[T]](0 seconds, 60 seconds, Datum.empty[T](collector)) { previous =>
      val datum = Datum[T](collector)
      datum.label match {
        case good:GoodLabel =>
          datum
        case BadLabel(product, origin, error) =>
          previous.label match {
            case GoodLabel(_,_,bb) if bb.isStale =>
              log.error(s"Crawl of $product from $origin failed, leaving previously crawled STALE data (${bb.age.getStandardSeconds} seconds old)", error)
            case GoodLabel(_,_,bb) if !bb.isStale =>
              log.warn(s"Crawl of $product from $origin failed, leaving previously crawled data (${bb.age.getStandardSeconds} seconds old)", error)
          }
          previous
      }
    }
    collector -> agent
  }.toMap

  def get(collector: Collector[T]): Datum[T] = datumAgents(collector)()

}