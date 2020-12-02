package agent

import org.joda.time.{DateTime, Duration}

import scala.util.Try
import scala.util.control.NonFatal
import scala.language.postfixOps
import play.api.libs.json._
import utils.{Logging, Marker}
import play.api.mvc.Call
import software.amazon.awssdk.regions.Region

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

case class AWSAccount(accountNumber: Option[String], accountName: String)

sealed trait AwsRegionType
case object Global extends AwsRegionType
case object Regional extends AwsRegionType

abstract class CollectorSet[T](val resource:ResourceType, accounts: Accounts) extends Logging {
  /** AWS services are either global or regional. AWS collectors should specify which to ensure that the APIs are used
   * correctly. */
  def awsRegionType: Option[AwsRegionType]
  /** Create a collector for the given origin (this is a partial function because not all collectors support
   *  all types of origin */
  def lookupCollector:PartialFunction[Origin, Collector[T]]

  def isOriginRegionType(regionType: Option[AwsRegionType])(origin: Origin): Boolean = {
    (origin, regionType) match {
      case (AmazonOrigin(_, region, _, _, _, _, _, _), Some(Global)) if region != Region.AWS_GLOBAL.id => false
      case (AmazonOrigin(_, region, _, _, _, _, _, _), Some(Regional)) if region == Region.AWS_GLOBAL.id => false
      case _ => true
    }
  }
  lazy val collectors: Seq[Collector[T]] =
    accounts
      .forResource(resource.name)
      .filter(isOriginRegionType(awsRegionType))
      .flatMap(lookupCollector.lift)
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
case class Label(resourceType: ResourceType, origin:Origin, itemCount:Int, createdAt:DateTime = new DateTime(), error:Option[Throwable] = None) extends Marker {
  lazy val isError: Boolean = error.isDefined
  lazy val status: String = if (isError) "error" else "success"
  lazy val bestBefore: BestBefore = BestBefore(createdAt, origin.crawlRate(resourceType.name).shelfLife, error = isError)

  override def toMarkerMap: Map[String, Any] = Map("resource" -> resourceType.name, "account" -> origin.account)
}

case class ResourceType(name: String)

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