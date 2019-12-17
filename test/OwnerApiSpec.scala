import play.api.mvc._
import play.api.test._

object OwnerApiSpec extends PlaySpecification with Results {
  "Getting the owners for a stack should return a successful status when the parameters are set correctly - ie. the stack is defined" in new WithApplicationLoader(new PrismApplicationLoader()) {
    val result = route(app, FakeRequest(GET, "/owners/forStack/stackname")).get
    contentType(result) must beSome("application/json")
    status(result) must equalTo(OK)
  }
}
