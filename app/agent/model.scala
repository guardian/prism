package agent

import org.joda.time.{DateTime, Duration}

import scala.util.Try
import scala.util.control.NonFatal
import scala.language.postfixOps
import play.api.libs.json._
import utils.Logging
import play.api.mvc.Call
import scala.concurrent.duration._

trait IndexedItem {
  def arn: String
  def callFromArn: String => Call
  def call: Call = callFromArn(arn)
  def fieldIndex: Map[String, String] = Map("arn" -> arn)
}

trait IndexedItemWithStage extends IndexedItem {
  val stage: Option[String] = None
}

trait IndexedItemWithStack extends IndexedItem {
  val stack: Option[String] = None
}

abstract class CollectorSet[T](val resource: ResourceType, val crawlRate: CrawlRate, accounts: Accounts) extends Logging {
  def lookupCollector:PartialFunction[Origin, Collector[T]]
  def collectorFor(origin:Origin): Option[Collector[T]] = {
    if (lookupCollector.isDefinedAt(origin)) Some(lookupCollector(origin)) else None
  }
  lazy val collectors: Seq[Collector[T]] = accounts.forResource(resource.name).flatMap(collectorFor)
}

trait Collector[T] {
  def crawl:Iterable[T]
  def origin:Origin
  def resource:ResourceType
  def crawlRate: CrawlRate
}

object Datum {
  def apply[T](collector: Collector[T]): Datum[T] = {
    Try {
      val items = collector.crawl.toSeq
      Datum(Label(collector, items.size), items)
    } recover {
      case NonFatal(t) =>
        Datum[T](Label(collector, t), Nil)
    } get
  }
  def empty[T](collector: Collector[T]): Datum[T] = Datum(Label(collector, new IllegalStateException("First crawl not yet done")), Nil)
}
case class Datum[T](label:Label, data:Seq[T])

object Label {
  def apply[T](c: Collector[T], itemCount: Int): Label = Label(c.resource, c.origin, itemCount)
  def apply[T](c: Collector[T], error: Throwable): Label = Label(c.resource, c.origin, 0, error = Some(error))
}
case class Label(resourceType: ResourceType, origin:Origin, itemCount:Int, createdAt:DateTime = new DateTime(), error:Option[Throwable] = None) {
  lazy val isError: Boolean = error.isDefined
  lazy val status: String = if (isError) "error" else "success"
  // TODO: improve the below
  lazy val bestBefore: BestBefore = BestBefore(createdAt, origin.crawlRate("")(resourceType.name).shelfLife, error = isError)
}

case class ResourceType(name: String) //shelfLife: FiniteDuration, refreshPeriod: FiniteDuration )

case class CrawlRate(shelfLife: FiniteDuration, refreshPeriod: FiniteDuration)

case class BestBefore(created:DateTime, shelfLife:FiniteDuration, error:Boolean) {
  val bestBefore:DateTime = created plus Duration.millis(shelfLife.toMillis)
  def isStale:Boolean = error || (new DateTime() compareTo bestBefore) >= 0
  def age:Duration = new Duration(created, new DateTime)
}

trait JsonCollector[T] extends JsonCollectorTranslator[T,T] with Logging {
  def translate(input: T): T = input
}

trait JsonCollectorTranslator[F,T] extends Collector[T] with Logging {
  def origin:JsonOrigin
  def json:JsValue = origin.data(resource)
  def crawlJson(implicit writes:Reads[F]):Iterable[T] = {
    Json.fromJson[Seq[F]](json) match {
      case JsError(errors) =>
        val failure = s"Encountered failure to parse json source: $errors"
        log.error(failure)
        throw new IllegalArgumentException(failure)
      case JsSuccess(result, _) => result.map(translate)
    }
  }
  def translate(input: F): T
}