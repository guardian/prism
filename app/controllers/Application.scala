package controllers

import play.api.mvc._
import play.api.Play

object Application extends Controller {

  def indexV1 = Action {
    import play.api.Play.current
    Ok(views.html.apiv1(Play.routes.map(_.documentation).getOrElse(Nil)))
  }
  def index = Action { Ok(views.html.index()) }

}