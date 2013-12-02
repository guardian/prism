package collectors

import java.net.URI
import org.joda.time.{Duration, DateTime}

trait Origin {}
case class AmazonOrigin(account:String, region:String, accessKey:String, secretKey:String) extends Origin
case class OpenstackOrigin(endpoint:URI, region:String, user:String, secret:String) extends Origin

trait Collector {
  def origin:Origin
  def product:Product
}

trait Label {
  def origin:Origin
  def bestBefore:BestBefore
  def product:Product
}

trait Product {
  def name: String
}

case class ProductName( name: String ) extends Product

case class BestBefore(created:DateTime, shelfLife:Duration) {
  val bestBefore:DateTime = created plus shelfLife
  def isStale:Boolean = (new DateTime() compareTo bestBefore) >= 0
}