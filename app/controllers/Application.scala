package controllers

import play.api.mvc._
import play.api.Play

object Application extends Controller {

  def index = Action {
    import play.api.Play.current
    Ok(views.html.index(Play.routes.map(_.documentation).getOrElse(Nil)))
  }

}