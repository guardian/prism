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

  def instanceJson(instance: Host, expand: Boolean = false)(implicit request: RequestHeader): JsValue = {
    val json = Json.toJson(instance).as[JsObject]
    val filtered = if (expand) json else JsObject(json.fields.filter(List("id") contains _._1))
    filtered ++ Json.obj("meta"-> Json.obj(
      "href" -> routes.Api.instance(instance.id).absoluteURL()
    ))
  }

  def instanceList = Action { implicit request =>
    ApiResult {
      val di = DeployInfoManager.deployInfo
      val expand = request.getQueryString("_expand").isDefined
      Json.obj(
        "instances" -> di.hosts.map(host => instanceJson(host, expand))
      )
    }
  }
  def instance(id:String) = Action { implicit request =>
    val instance = DeployInfoManager.deployInfo.hosts.find(_.id == id).getOrElse(throw new IllegalApiCallException(Json.obj("id" -> "unknown ID")))
    ApiResult(instanceJson(instance, true))
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

  def appList = TODO
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