package controllers

import play.api.mvc._
import play.api.libs.json._
import deployinfo.DeployInfoManager
import play.api.http.Status
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration.Duration

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

  def instanceList = Action { implicit request =>
    ApiResult {
      val di = DeployInfoManager.deployInfo
      Json.obj(
        "instances" -> di.hosts.map(host => Json.obj(
          "id" -> host.id,
          "href" -> routes.Api.instance(host.id).absoluteURL()
        ))
      )
    }
  }
  def instance(id:String) = Action { implicit request =>
    val instance = DeployInfoManager.deployInfo.hosts.find(_.id == id).getOrElse(throw new IllegalApiCallException(Json.obj("id" -> "unknown ID")))
    ApiResult(Json.obj("id" -> instance.id, "dump" -> instance.toString))
  }

  def appList = TODO
  def app(id:String) = TODO

  def stackList = TODO
  def stack(id:String) = TODO

}