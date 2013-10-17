package controllers

import play.api._
import play.api.mvc._

object Application extends Controller {

  def indexV1 = Action { Ok(views.html.apiv1()) }
  def index = Action { Ok(views.html.index()) }

}