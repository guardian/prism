package agent

import controllers.Prism
import play.api.mvc.Call

case class Reference[T](arn:String, fields: Map[String, String] = Map.empty, prism: Prism = null)(implicit arnLookup: ArnLookup[T]) {
  def dereference(implicit arnLookup: ArnLookup[T]): Option[(Label,T)] = arnLookup.item(arn, prism)
  def call(implicit arnLookup: ArnLookup[T]): Call = arnLookup.call(arn)
}

trait ArnLookup[T] {
  def item(arn: String, prism: Prism = null): Option[(Label,T)]
  def call(arn: String): Call
}