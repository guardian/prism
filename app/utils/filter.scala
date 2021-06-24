package utils

import scala.util.matching.Regex
import play.api.libs.json._
import play.api.mvc.RequestHeader

trait Matchable {
  def isMatch(value: JsValue): Boolean
}
case class StringMatchable(matcher: String) extends Matchable {
  def isMatch(value: JsValue): Boolean = value match {
    case JsString(str) => str == matcher
    case JsNumber(num) => num.toString == matcher
    case JsArray(seq) => seq.exists(isMatch)
    case JsBoolean(bool) => bool.toString.equalsIgnoreCase(matcher)
    case _ => false
  }
}
case class RegExMatchable(matcher: Regex) extends Matchable {
  private def isRegExMatch(value: String) = matcher.unapplySeq(value).isDefined
  def isMatch(value: JsValue): Boolean = value match {
    case JsString(str) => isRegExMatch(str)
    case JsNumber(num) => isRegExMatch(num.toString)
    case JsArray(seq) => seq.exists(isMatch)
    case JsBoolean(bool) => isRegExMatch(bool.toString)
    case _ => false
  }
}
case object ExistsMatchable extends Matchable {
  override def isMatch(value: JsValue): Boolean = value match {
    case JsNull => false
    case _ => true
  }
}
case class InverseMatchable(matcher: Matchable) extends Matchable {
  def isMatch(value: JsValue): Boolean = !matcher.isMatch(value)
  def inverse: Boolean = true
}
case class ResourceFilter(filter:Map[String,Seq[Matchable]]) extends Matchable with Logging {
  def isMatch(json: JsValue): Boolean = {
    filter.map { case (fieldName, matchers) =>
      matchers -> fieldName.split('.').foldLeft(json){case (jv, part) => (jv \ part).getOrElse(JsNull)}
    } forall { case (matchers, fieldValue) => matchers exists (_.isMatch(fieldValue)) }
  }

  def isMatch(map: Map[String, String]): Boolean = {
    filter.map { case (field, values) =>
      map.get(field) match {
        case None => true // no constraint? then match
        case Some(string) => values exists (_.isMatch(JsString(string)))
      }
    } forall(ok => ok)
  }
}
object ResourceFilter {
  // list of request keys that should be ignored
  val DenyList = Set("callback")

  val InverseRegexMatch: Regex = """^([a-zA-Z0-9.]*)(?:!~|~!)$""".r
  val InverseMatch: Regex = """^([a-zA-Z0-9.]*)!$""".r
  val RegexMatch: Regex = """^([a-zA-Z0-9.]*)~$""".r
  val SimpleMatch: Regex = """^([a-zA-Z0-9.]*)$""".r

  def matcher(key:String, value:String): Option[(String, Matchable)] = {
    key match {
      case InverseRegexMatch(bareKey) => Some(bareKey -> InverseMatchable(RegExMatchable(value.r)))
      case RegexMatch(bareKey) => Some(bareKey -> RegExMatchable(value.r))
      case InverseMatch(bareKey) if value.nonEmpty => Some(bareKey -> InverseMatchable(StringMatchable(value)))
      case SimpleMatch(bareKey) if value.nonEmpty => Some(bareKey -> StringMatchable(value))
      case InverseMatch(bareKey) => Some(bareKey -> InverseMatchable(ExistsMatchable))
      case SimpleMatch(bareKey) => Some(bareKey -> ExistsMatchable)
      case _ => None
    }
  }

  def fromRequest(implicit request: RequestHeader): ResourceFilter = fromRequestWithDefaults()

  def fromRequestWithDefaults(defaults: (String,String)*)(implicit request: RequestHeader): ResourceFilter = {
    val defaultKeys = defaults.flatMap{ d => matcher(d._1,d._2) }.groupBy(_._1).view.mapValues(_.map(_._2))
    val filterKeys = request.queryString.view.filterKeys(key => !DenyList.contains(key)).toSeq.flatMap { case (key, values) =>
      values.flatMap(matcher(key,_))
    }.groupBy(_._1).view.mapValues(_.map(_._2))
    ResourceFilter((defaultKeys ++ filterKeys).toMap)
  }

  lazy val all: Matchable = (_: JsValue) => true
}
