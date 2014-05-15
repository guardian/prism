package agent

import play.api.mvc.Call

case class Reference[T](id:String, fields: Map[String, String] = Map.empty)(implicit idLookup: IdLookup[T]) {
  def dereference(implicit idLookup: IdLookup[T]): Option[(Label,T)] = idLookup.item(id)
  def call(implicit idLookup: IdLookup[T]): Call = idLookup.call(id)
}

trait IdLookup[T] {
  def item(id: String): Option[(Label,T)]
  def call(id: String): Call
}