package controllers

import play.api.Configuration
import play.api.mvc._
import play.api.routing.Router

// router parameter is by-name because otherwise StackOverflowError is thrown due to initialisation order problem in ApplicationLoader
class Application(configuration: Configuration, router: => Router) extends Controller {
  def index = Action {
    Ok(views.html.index(router.documentation))
  }

  def config = Action {
    Ok(configuration.underlying.root().render())
  }
}