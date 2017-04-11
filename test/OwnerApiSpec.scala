import controllers.OwnerApi
import play.api.mvc._
import play.api.test._

object OwnerApiSpec extends PlaySpecification with Results {

  "Getting the owners for a stack should return a successful status when the parameters are set correctly - ie. the stack is defined" in {
    implicit val request = FakeRequest(GET, "/owners/for?stack=stackname")
    val result = OwnerApi.ownerForStack(request)
    contentType(result) must beSome("application/json")
    status(result) must equalTo(OK)
  }

  "Getting the owners for a stack should return a bad request status if only the app and the stage are defined" in {
    implicit val request = FakeRequest(GET, "/owners/for?stage=PROD&app=janus")
    val result = OwnerApi.ownerForStack(request)
    contentType(result) must beSome("text/plain")
    status(result) must equalTo(BAD_REQUEST)
  }
}
