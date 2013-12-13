package collectors

import org.joda.time.{Duration, DateTime}
import scala.util.Try
import scala.util.control.NonFatal
import scala.language.postfixOps
import play.api.libs.json._
import scala.io.Source
import java.net.{URLConnection, URL, URLStreamHandler}
import java.io.FileNotFoundException
import play.api.libs.json.JsArray
import scala.Some
import utils.Logging

trait Origin {
  def vendor: String
  def account: String
  def filterMap: Map[String,String] = Map.empty
}

case class AmazonOrigin(account:String, region:String, accessKey:String)(val secretKey:String) extends Origin {
  lazy val vendor = "aws"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "accountName" -> account)
}
case class OpenstackOrigin(endpoint:String, region:String, tenant:String, user:String)(val secret:String) extends Origin {
  lazy val vendor = "openstack"
  lazy val account = s"$tenant@$region"
  override lazy val filterMap = Map("vendor" -> vendor, "region" -> region, "account" -> tenant, "accountName" -> tenant)
}
case class JsonOrigin(vendor:String, account:String, url:String) extends Origin {
  private val classpathHandler = new URLStreamHandler {
    override def openConnection(u: URL): URLConnection = {
      Option(getClass.getResource(u.getPath)).map(_.openConnection()).getOrElse{
        throw new FileNotFoundException("%s not found on classpath" format u.getPath)
      }
    }
  }

  def data(resource:Resource):JsValue = {
    val actualUrl = url.replace("%resource%", resource.name) match {
      case classPathLocation if classPathLocation.startsWith("classpath:") => new URL(null, classPathLocation, classpathHandler)
      case otherURL => new URL(otherURL)
    }
    val jsonText = Source.fromURL(actualUrl, "utf-8").getLines().mkString
    Json.parse(jsonText)
  }
}

trait Collector[T] {
  def crawl:Iterable[T]
  def origin:Origin
  def resource:Resource
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
case class Label(resource:Resource, origin:Origin, createdAt:DateTime = new DateTime(), error:Option[Throwable] = None) {
  lazy val isError = error.isDefined
  lazy val status = if (isError) "error" else "success"
  lazy val bestBefore = BestBefore(createdAt, resource.shelfLife, error = isError)
}

case class Resource( name: String, shelfLife: Duration )

case class BestBefore(created:DateTime, shelfLife:Duration, error:Boolean) {
  val bestBefore:DateTime = created plus shelfLife
  def isStale:Boolean = error || (new DateTime() compareTo bestBefore) >= 0
  def age:Duration = new Duration(created, new DateTime)
}

trait JsonCollector[T] extends Logging {
  def origin:JsonOrigin
  def resource:Resource
  def json:JsValue = origin.data(resource)
  def crawlJson(implicit writes:Reads[T]):Iterable[T] = {
    try {
      Json.fromJson[Seq[T]](json) match {
        case JsError(errors) =>
          log.warn(s"Encountered failure to parse json source: $errors")
          Nil
        case JsSuccess(result, _) => result
      }
    } catch {
      case NonFatal(t) =>
        println(s"Failed ${t.getMessage}")
        Nil
    }
  }
}