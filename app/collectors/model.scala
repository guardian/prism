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
import utils.Logging
import conf.Configuration.accounts
import play.api.mvc.Call

trait Origin {
  def vendor: String
  def account: String
  def filterMap: Map[String,String] = Map.empty
  def resources: Set[String]
}

case class AmazonOrigin(account:String, region:String, accessKey:String, resources:Set[String])(val secretKey:String) extends Origin {
  lazy val vendor = "aws"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "accountName" -> account)
}
case class OpenstackOrigin(endpoint:String, region:String, tenant:String, user:String, resources:Set[String])(val secret:String) extends Origin {
  lazy val vendor = "openstack"
  lazy val account = s"$tenant@$region"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "account" -> tenant, "accountName" -> tenant)
}
case class JsonOrigin(vendor:String, account:String, url:String, resources:Set[String]) extends Origin {
  private val classpathHandler = new URLStreamHandler {
    override def openConnection(u: URL): URLConnection = {
      Option(getClass.getResource(u.getPath)).map(_.openConnection()).getOrElse{
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      }
    }
  }

  def data(resource:ResourceType):JsValue = {
    val actualUrl = url.replace("%resource%", resource.name) match {
      case classPathLocation if classPathLocation.startsWith("classpath:") => new URL(null, classPathLocation, classpathHandler)
      case otherURL => new URL(otherURL)
    }
    val jsonText = Source.fromURL(actualUrl, "utf-8").getLines().mkString
    Json.parse(jsonText)
  }
}

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
  lazy val collectors = accounts.forResource(resource.name).flatMap(collectorFor(_))
}

trait Collector[T] {
  def crawl:Iterable[T]
  def origin:Origin
  def resource:ResourceType
}

object Datum {
  def apply[T](collector: Collector[T]): Datum[T] = {
    Try {
      Datum(Label(collector), collector.crawl.toSeq)
    } recover {
      case NonFatal(t) =>
        Datum[T](Label(collector, t), Nil)
    } get
  }
  def empty[T](collector: Collector[T]): Datum[T] = Datum(Label(collector, new IllegalStateException("First crawl not yet done")), Nil)
}
case class Datum[T](label:Label, data:Seq[T])

object Label {
  def apply[T](c: Collector[T]): Label = Label(c.resource, c.origin)
  def apply[T](c: Collector[T], error: Throwable): Label = Label(c.resource, c.origin, error = Some(error))
}
case class Label(resource:ResourceType, origin:Origin, createdAt:DateTime = new DateTime(), error:Option[Throwable] = None) {
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
          log.warn(s"Encountered failure to parse json source: $errors")
          Nil
        case JsSuccess(result, _) => result.map(translate)
      }
    } catch {
      case NonFatal(t) =>
        println(s"Failed ${t.getMessage}")
        Nil
    }
  }
  def translate(input: F): T
}