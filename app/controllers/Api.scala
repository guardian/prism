package controllers

import play.api.mvc._
import play.api.libs.json.{JsValue, Json}
import play.api.libs.Jsonp
import scala.Some
import play.api.mvc.SimpleResult
import deployinfo.DeployInfoManager

object OkJson {
  def apply[A](json: JsValue)(implicit request:Request[A]): SimpleResult = {
    request.queryString.get("callback").flatMap(_.headOption) match {
      case Some(callback) => Results.Ok(Jsonp(callback, json))
      case None => Results.Ok(json)
    }
  }
}

object Api extends Controller {

  def instanceList = Action { implicit request =>
    val di = DeployInfoManager.deployInfo
    OkJson(Json.obj("instances" -> di.hosts.map(_.name)))
  }
  def instance(id:String) = Action { implicit request => OkJson(Json.obj()) }

  def appList = TODO
  def app(id:String) = TODO

  def stackList = TODO
  def stack(id:String) = TODO

}