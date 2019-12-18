package controllers

import play.api.Configuration
import play.api.mvc._
import play.api.routing.Router

class Application(configuration: Configuration, router: => Router) extends Controller {
  def index = Action {
    Ok(views.html.index(router.documentation))
  }

  def config = Action {
    Ok(configuration.underlying.root().render())
  }
}