package controllers

import com.typesafe.config.Config
import play.api.Configuration
import play.api.mvc._

//noinspection TypeAnnotation
class Application (cc: ControllerComponents, configuration: Configuration, documentation: () => Seq[(String, String, String)]) extends AbstractController(cc) {
  def index = Action {
    Ok(views.html.index(documentation()))
  }

  def config = Action {
    Ok(configuration.underlying.root().render())
  }
}