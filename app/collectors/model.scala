package collectors

import org.joda.time.{Duration, DateTime}
import scala.util.Try
import scala.util.control.NonFatal
import scala.language.postfixOps
import play.api.libs.json._
import scala.io.Source
import java.net.{URLConnection, URL, URLStreamHandler}
import java.io.FileNotFoundException
import scala.Some
import utils.{GoogleDoc, Logging}
import conf.Configuration.accounts
import play.api.mvc.Call
import org.jclouds.domain.{LocationScope, LocationBuilder}
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json.Json.JsValueWrapper

trait IndexedItem {
  def id: String
  def callFromId: String => Call
  def call: Call = callFromId(id)
  def fieldIndex: Map[String, String] = Map("id" -> id)
}

abstract class CollectorSet[T](val resource:ResourceType) extends Logging {
  def lookupCollector:PartialFunction[Origin, Collector[T]]
  def collectorFor(origin:Origin): Option[Collector[T]] = {
    if (lookupCollector.isDefinedAt(origin)) Some(lookupCollector(origin)) else None
  }
  lazy val collectors = Accounts.forResource(resource.name).flatMap(collectorFor)
}

trait Collector[T] {
  def crawl:Iterable[T]
  def origin:Origin
  def resource:ResourceType
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
case class Label(resource:ResourceType, origin:Origin, itemCount:Int, createdAt:DateTime = new DateTime(), error:Option[Throwable] = None) {
  lazy val isError = error.isDefined
  lazy val status = if (isError) "error" else "success"
  lazy val bestBefore = BestBefore(createdAt, resource.shelfLife, error = isError)
}

case class ResourceType( name: String, shelfLife: Duration )

case class BestBefore(created:DateTime, shelfLife:Duration, error:Boolean) {
  val bestBefore:DateTime = created plus shelfLife
  def isStale:Boolean = error || (new DateTime() compareTo bestBefore) >= 0
  def age:Duration = new Duration(created, new DateTime)
}

trait JsonCollector[T] extends JsonCollectorTranslator[T,T] with Logging {
  def translate(input: T) = input
}

trait JsonCollectorTranslator[F,T] extends Collector[T] with Logging {
  def origin:JsonOrigin
  def json:JsValue = origin.data(resource)
  def crawlJson(implicit writes:Reads[F]):Iterable[T] = {
    try {
      Json.fromJson[Seq[F]](json) match {
        case JsError(errors) =>
          val failure = s"Encountered failure to parse json source: $errors"
          log.error(failure)
          throw new IllegalArgumentException(failure)
        case JsSuccess(result, _) => result.map(translate)
      }
    }
  }
  def translate(input: F): T
}

trait GoogleDocCollector[T] extends Collector[T] {
  def origin:GoogleDocOrigin
  def csvData:List[List[String]] = Await.result(GoogleDoc.getCsvForDoc(origin.docUrl), 1 minute)
}