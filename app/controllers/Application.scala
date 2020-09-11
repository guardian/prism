package controllers

import javax.inject.Inject
import play.api.Play
import play.api.mvc._

//
//object Application extends Controller {
//
//  def index = Action {
//    import play.api.Play.current
//    Ok(views.html.index(Play.routes.documentation))
//  }
//
//  def config = Action {
//    Ok(play.api.Play.current.configuration.underlying.root().render())
//  }

//}

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */

class Application @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  /**
   * Create an Action to render an HTML page with a welcome message.
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index = Action {
    Ok(views.html.index(Seq()))
  }

}