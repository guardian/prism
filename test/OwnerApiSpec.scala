import controllers.OwnerApi
import play.api.mvc._
import play.api.test._

import scala.concurrent.ExecutionContext

object OwnerApiSpec extends PlaySpecification with Results {
  "Getting the owners for a stack should return a successful status when the parameters are set correctly - ie. the stack is defined" in {
    val components = Helpers.stubControllerComponents()
    implicit val executionContext: ExecutionContext = components.executionContext
    val controller = new OwnerApi(components)

    val result = controller.ownerForStack("stackname").apply(FakeRequest())
    contentType(result) must beSome("application/json")
    status(result) must equalTo(OK)
  }
}
