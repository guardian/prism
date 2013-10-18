package controllers

import play.api.mvc._
import play.api.libs.json._
import deployinfo.{Host, DeployInfoManager}
import play.api.http.Status
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration.Duration
import utils.Json._
import scala.util.matching.Regex

// use this when a
case class IllegalApiCallException(failure:JsObject, status:Int = Status.BAD_REQUEST)
  extends RuntimeException(failure.fields.map(f => s"${f._1}: ${f._2}").mkString("; "))

object ApiResult {
  def apply(block: => JsValue): SimpleResult = Await.result(ApiResult.async(Future.successful(block)), Duration.Inf)
  def async(block: => Future[JsValue]): Future[SimpleResult] = {
    try {
      block.map { data =>
        Results.Ok(Json.obj(
          "status" -> "success",
          "data" -> data
        ))
      }
    } catch {
      case IllegalApiCallException(failure, status) =>
        Future.successful(Results.Status(status)(Json.obj(
          "status" -> "fail",
          "data" -> failure
        )))
      case e:Exception =>
        Future.successful(Results.InternalServerError(Json.obj(
          "status" -> "error",
          "message" -> e.getMessage
        )))
    }
  }
}

object Api extends Controller {

  implicit val instanceWriter = Json.writes[Host]

  trait Matchable[T] {
    def isMatch(value: T): Boolean
  }
  case class StringMatchable(matcher: String) extends Matchable[String] {
    def isMatch(value: String): Boolean = value == matcher
  }
  case class RegExMatchable(matcher: Regex) extends Matchable[String] {
    def isMatch(value: String): Boolean = matcher.unapplySeq(value).isDefined
  }
  case class Filter(filter:Map[String,Seq[Matchable[String]]]) extends Matchable[JsValue] {
    def isMatch(json: JsValue): Boolean = {
      filter.map { case (field, values) =>
        val value = json \ field
        value match {
          case JsString(str) => values exists (_.isMatch(str))
          case JsNumber(int) => values exists (_.isMatch(int.toString))
          case JsArray(seq) =>
            seq.exists {
              case JsString(str) => values exists (_.isMatch(str))
              case _ => false
            }
          case _ => false
        }

      } forall(ok => ok)
    }
  }
  object Filter {
    def fromRequest(implicit request: RequestHeader): Filter = {
      val matcher = (value:String) => request.getQueryString("_match") match {
        case Some("regex") => RegExMatchable(value.r)
        case _ => StringMatchable(value)
      }
      val filterKeys = request.queryString.filterKeys(!_.startsWith("_")).mapValues {valueList => valueList.map(matcher)}
      Filter(filterKeys)
    }
    lazy val all = new Matchable[JsValue] {
      def isMatch(value: JsValue): Boolean = true
    }
  }

  def instanceJson(instance: Host, expand: Boolean = false, filter: Matchable[JsValue] = Filter.all)(implicit request: RequestHeader): Option[JsValue] = {
    val json = Json.toJson(instance).as[JsObject]
    if (filter.isMatch(json)) {
      val filtered = if (expand) json else JsObject(json.fields.filter(List("id") contains _._1))
      Some(filtered ++ Json.obj("meta"-> Json.obj(
        "href" -> routes.Api.instance(instance.id).absoluteURL()
      )))
    } else {
      None
    }
  }

  def instanceList = Action { implicit request =>
    ApiResult {
      val di = DeployInfoManager.deployInfo
      val expand = request.getQueryString("_expand").isDefined
      val filter = Filter.fromRequest
      Json.obj(
        "instances" -> di.hosts.flatMap(host => instanceJson(host, expand, filter))
      )
    }
  }
  def instance(id:String) = Action { implicit request =>
    ApiResult {
      val instance = DeployInfoManager.deployInfo.hosts.find(_.id == id).getOrElse(throw new IllegalApiCallException(Json.obj("id" -> "unknown ID")))
      instanceJson(instance, true).get
    }
  }

  def instanceSummary(transform: Host => Seq[JsValue]) = {
    def sortString(jsv: JsValue):String =
      jsv match {
        case JsString(str) => str
        case JsArray(seq) => seq.map(sortString).mkString
        case JsObject(fields) => fields.map{case(key, value) => s"${key}${sortString(value)}"}.mkString
        case _ => ""
      }

    DeployInfoManager.deployInfo.hosts.flatMap(transform).distinct.sortBy(sortString)
  }

  def roleList = Action { implicit request =>
    ApiResult { Json.obj( "roles" -> instanceSummary(host => Seq(Json.toJson(host.role))) ) }
  }
  def mainclassList = Action { implicit request =>
    ApiResult { Json.obj("mainclasses" -> instanceSummary(host => host.mainclasses.map(Json.toJson(_)))) }
  }
  def stackList = Action { implicit request =>
    ApiResult { Json.obj("stacks" -> instanceSummary(host => Seq(Json.toJson(host.stack)))) }
  }
  def stageList = Action { implicit request =>
    ApiResult { Json.obj("stages" -> instanceSummary(host => Seq(Json.toJson(host.stage)))) }
  }
  def regionList = Action { implicit request =>
    ApiResult { Json.obj("regions" -> instanceSummary(host => Seq(Json.toJson(host.region)))) }
  }
  def accountList = Action { implicit request =>
    ApiResult { Json.obj("accounts" -> instanceSummary(host => Seq(Json.toJson(host.account)))) }
  }
  def vendorList = Action { implicit request =>
    ApiResult { Json.obj("vendors" -> instanceSummary(host => Seq(Json.toJson(host.vendor)))) }
  }

  def appList = Action { implicit request =>
    val filter = Filter.fromRequest
    ApiResult {
      val apps = instanceSummary{ host =>
        host.apps.flatMap{ app =>
          host.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app))).filter(filter.isMatch)
        }.toSeq
      }
      Json.obj(
        "apps" -> apps
      )
    }
  }

}