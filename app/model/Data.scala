package model

trait Datum {
  def id:String
  def fields:Map[String, Any]
}