package controllers

import play.api.mvc._
import play.api.libs.json._
import deployinfo.DeployInfoManager
import play.api.http.Status
import scala.concurrent.Future
import play.api.libs.concurrent.Execution.Implicits._

// use this when a
case class IllegalApiCallException(failure:JsObject, status:Int = Status.BAD_REQUEST)
  extends RuntimeException(failure.fields.map(f => s"${f._1}: ${f._2}").mkString("; "))

object ApiResult {
  def apply(block: => JsValue): Future[SimpleResult] = ApiResult.async(Future.successful(block))
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

  def instanceList = Action.async { implicit request =>
    ApiResult {
      val di = DeployInfoManager.deployInfo
      Json.obj("instances" -> di.hosts.map(_.name))
    }
  }
  def instance(id:String) = Action.async { implicit request => ApiResult(Json.obj()) }

  def appList = TODO
  def app(id:String) = TODO

  def stackList = TODO
  def stack(id:String) = TODO

}