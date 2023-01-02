package controllers

import play.api.mvc._

//noinspection TypeAnnotation
class Application(
    cc: ControllerComponents,
    documentation: () => Seq[(String, String, String)]
) extends AbstractController(cc) {
  def index = Action {
    Ok(views.html.index(documentation()))
  }
}
