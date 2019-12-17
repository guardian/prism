package controllers

import play.api.Configuration
import play.api.mvc._
import router.Routes

class Application(routes: Routes, configuration: Configuration) extends Controller {
  def index = Action {
    Ok(views.html.index(routes.documentation))
  }

  def config = Action {
    Ok(configuration.underlying.root().render())
  }
}