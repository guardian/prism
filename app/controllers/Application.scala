package controllers

import com.typesafe.config.Config
import javax.inject.Inject
import play.api.mvc._

class Application @Inject()(cc: ControllerComponents, underlyingConfig: Config) extends AbstractController(cc) {
  private var _documentation: Seq[(String, String, String)] = Seq()

  def documentation: Seq[(String, String, String)] = _documentation
  def documentation_= (newValue: Seq[(String, String, String)]): Unit = {
    _documentation = newValue
  }

  def index: Action[AnyContent] = Action {
    Ok(views.html.index(_documentation))
  }

    def config: Action[AnyContent] = Action {
      Ok(underlyingConfig.root().render())
    }
}