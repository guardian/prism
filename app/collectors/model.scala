package collectors

import org.joda.time.{Duration, DateTime}
import scala.util.Try
import scala.util.control.NonFatal
import scala.language.postfixOps

trait Origin {
  def vendor: String
  def account: String
}
case class AmazonOrigin(account:String, region:String, accessKey:String)(val secretKey:String) extends Origin {
  lazy val vendor = "aws"
}
case class OpenstackOrigin(endpoint:String, region:String, tenant:String, user:String)(val secret:String) extends Origin {
  lazy val vendor = "openstack"
  lazy val account = s"$tenant@$region"
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

trait Label {
  def resource:Resource
  def origin:Origin
  def isError:Boolean
}

object Label {
  def apply[T](c: Collector[T]) =
    GoodLabel(c.product, c.origin, BestBefore(new DateTime(), c.product.shelfLife))
  def apply[T](c: Collector[T], error: Throwable) =
    BadLabel(c.product, c.origin, error)
}
case class GoodLabel(resource:Resource, origin:Origin, bestBefore:BestBefore) extends Label { val isError = false }
case class BadLabel(resource:Resource, origin:Origin, error:Throwable) extends Label { val isError = true }

case class Resource( name: String, shelfLife: Duration )

case class BestBefore(created:DateTime, shelfLife:Duration) {
  val bestBefore:DateTime = created plus shelfLife
  def isStale:Boolean = (new DateTime() compareTo bestBefore) >= 0
  def age:Duration = new Duration(created, new DateTime)
}