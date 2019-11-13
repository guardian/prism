package controllers

import play.api.mvc._
import play.api.Play

class Application extends Controller {

  def index = Action {
    import play.api.Play.current
    Ok(views.html.index(Play.routes.documentation))
  }

  def config = Action {
    Ok(play.api.Play.current.configuration.underlying.root().render())
  }

}