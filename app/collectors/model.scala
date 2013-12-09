package collectors

import org.joda.time.{Duration, DateTime}
import scala.util.Try
import scala.util.control.NonFatal
import scala.language.postfixOps

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

trait Collector[T] {
  def crawl:Iterable[T]
  def origin:Origin
  def product:Resource
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
  def apply[T](c: Collector[T]): Label = Label(c.product, c.origin)
  def apply[T](c: Collector[T], error: Throwable): Label = Label(c.product, c.origin, error = Some(error))
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