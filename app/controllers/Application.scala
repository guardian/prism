package controllers

import play.api.Configuration
import play.api.mvc._

class Application(configuration: Configuration) extends Controller {
  def index = Action {
    // FIXME: How to inject routes at compile time to get at routes.documentation?
    // Ok(views.html.index(routes.documentation))
    Ok("ok")
  }

  def config = Action {
    Ok(configuration.underlying.root().render())
  }
}