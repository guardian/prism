package agent

import play.api.mvc.Call

case class Reference[T](arn:String, fields: Map[String, String] = Map.empty)(implicit arnLookup: ArnLookup[T]) {
  def dereference(implicit arnLookup: ArnLookup[T]): Option[(Label,T)] = arnLookup.item(arn)
  def call(implicit arnLookup: ArnLookup[T]): Call = arnLookup.call(arn)
}

trait ArnLookup[T] {
  def item(arn: String): Option[(Label,T)]
  def call(arn: String): Call
}