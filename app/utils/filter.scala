package utils

import scala.util.matching.Regex
import play.api.libs.json._
import play.api.mvc.RequestHeader

trait Matchable[T] {
  def isMatch(value: T): Boolean
}
case class StringMatchable(matcher: String) extends Matchable[String] {
  def isMatch(value: String): Boolean = value == matcher
}
case class RegExMatchable(matcher: Regex) extends Matchable[String] {
  def isMatch(value: String): Boolean = matcher.unapplySeq(value).isDefined
}
case class InverseMatchable[T](matcher: Matchable[T]) extends Matchable[T] {
  def isMatch(value: T): Boolean = !matcher.isMatch(value)
}
case class ResourceFilter(filter:Map[String,Seq[Matchable[String]]]) extends Matchable[JsValue] with Logging {
  def isMatch(json: JsValue): Boolean = {
    filter.map { case (field, values) =>
      values -> field.split('.').foldLeft(json){case (jv, part) => (jv \ part).getOrElse(JsNull)}
    } forall {
      case (values, JsString(str)) => values exists (_.isMatch(str))
      case (values, JsNumber(int)) => values exists (_.isMatch(int.toString() ))
      case (values, JsArray(seq)) =>
        seq.exists {
          case JsString(str) => values exists (_.isMatch(str))
          case _ => false
        }
      case _ => false
    }
  }

  def isMatch(map: Map[String, String]): Boolean = {
    filter.map { case (field, values) =>
      val value = map.get(field)
      value match {
        case None => true // no constraint? then match
        case Some(string) => values exists (_.isMatch(string))
      }
    } forall(ok => ok)
  }
}
object ResourceFilter {
  // blacklist of request keys that should be ignored
  val Blacklist = Set("callback")

  val InverseRegexMatch: Regex = """^([a-zA-Z0-9.]*)(?:!~|~!)$""".r
  val InverseMatch: Regex = """^([a-zA-Z0-9.]*)!$""".r
  val RegexMatch: Regex = """^([a-zA-Z0-9.]*)~$""".r
  val SimpleMatch: Regex = """^([a-zA-Z0-9.]*)$""".r

  def matcher(key:String, value:String): Option[(String, Matchable[String])] = {
    key match {
      case InverseRegexMatch(bareKey) => Some(bareKey -> InverseMatchable(RegExMatchable(value.r)))
      case RegexMatch(bareKey) => Some(bareKey -> RegExMatchable(value.r))
      case InverseMatch(bareKey) => Some(bareKey -> InverseMatchable(StringMatchable(value)))
      case SimpleMatch(bareKey) => Some(bareKey -> StringMatchable(value))
      case _ => None
    }
  }

  def fromRequest(implicit request: RequestHeader): ResourceFilter = fromRequestWithDefaults()

  def fromRequestWithDefaults(defaults: (String,String)*)(implicit request: RequestHeader): ResourceFilter = {
    val defaultKeys = defaults.flatMap{ d => matcher(d._1,d._2) }.groupBy(_._1).view.mapValues(_.map(_._2))
    val filterKeys = request.queryString.view.filterKeys(key => !Blacklist.contains(key)).toSeq.flatMap { case (key, values) =>
      values.flatMap(matcher(key,_))
    }.groupBy(_._1).view.mapValues(_.map(_._2))
    ResourceFilter((defaultKeys ++ filterKeys).toMap)
  }

  lazy val all: Matchable[JsValue] = (_: JsValue) => true
}
