package controllers

import play.api.mvc._
import play.api.libs.json._
import deployinfo.{Host, DeployInfoManager}
import play.api.http.Status
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration.Duration
import utils.Json._

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

  def matchFilter(json: JsObject, filter: Map[String, Seq[String]]): Boolean = {
    filter.map { case (field, values) =>
      val value = json \ field
      value match {
        case JsString(str) => values contains str
        case JsNumber(int) => values contains int.toString
        case JsArray(seq) =>
          seq.exists { nestedValue =>
            nestedValue match {
              case JsString(str) => values contains str
              case _ => false
            }
          }
        case _ => false
      }

    } forall(ok => ok)
  }

  def instanceJson(instance: Host, expand: Boolean = false, filter: Map[String, Seq[String]] = Map.empty)(implicit request: RequestHeader): Option[JsValue] = {
    val json = Json.toJson(instance).as[JsObject]
    if (matchFilter(json, filter)) {
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
      val filter = request.queryString.filterKeys(!_.startsWith("_"))
      Json.obj(
        "instances" -> di.hosts.flatMap(host => instanceJson(host, expand, filter))
      )
    }
  }
  def instance(id:String) = Action { implicit request =>
    val instance = DeployInfoManager.deployInfo.hosts.find(_.id == id).getOrElse(throw new IllegalApiCallException(Json.obj("id" -> "unknown ID")))
    ApiResult(instanceJson(instance, true).get)
  }

  def roleList = Action { implicit request =>
    ApiResult {
      val roles = DeployInfoManager.deployInfo.hosts.map(_.role).distinct.sorted
      Json.obj(
        "roles" -> roles
      )
    }
  }

  def mainclassList = Action { implicit request =>
    ApiResult {
      val mainClasses = DeployInfoManager.deployInfo.hosts.map(_.mainclass).distinct.sorted
      Json.obj(
        "mainclasses" -> mainClasses
      )
    }
  }

  def appList = Action { implicit request =>
    ApiResult {
      val apps = DeployInfoManager.deployInfo.hosts.flatMap{ host =>
        host.stack.map{ stack =>
          host.apps.map(app => Map("app" -> app, "stack" -> stack))
        }
      }.distinct.flatten
      val filtered = request.getQueryString("stack").map(stackName => apps.filter(_("stack") == stackName)).getOrElse(apps)
      Json.obj(
        "apps" -> filtered
      )
    }
  }
  def app(id:String) = TODO

  def stackList = Action { implicit request =>
    ApiResult {
      val stacks = DeployInfoManager.deployInfo.hosts.flatMap(_.stack).distinct.sorted
      Json.obj(
        "stacks" -> stacks
      )
    }
  }
  def stack(id:String) = TODO

}