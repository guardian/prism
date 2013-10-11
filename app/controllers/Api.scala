package controllers

import play.api.mvc._
import play.api.libs.json._
import deployinfo.DeployInfoManager

object Api extends Controller {

  def instanceList = Action { implicit request =>
    val di = DeployInfoManager.deployInfo
    Ok(Json.obj("instances" -> di.hosts.map(_.name)))
  }
  def instance(id:String) = Action { implicit request => Ok(Json.obj()) }

  def appList = TODO
  def app(id:String) = TODO

  def stackList = TODO
  def stack(id:String) = TODO

}