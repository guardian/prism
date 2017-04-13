import controllers.OwnerApi
import play.api.mvc._
import play.api.test._

object OwnerApiSpec extends PlaySpecification with Results {
  "Getting the owners for a stack should return a successful status when the parameters are set correctly - ie. the stack is defined" in {
    implicit val request = FakeRequest(GET, "/owners/forStack/stackname")
    val result = OwnerApi.ownerForStack("stackname")(request)
    contentType(result) must beSome("application/json")
    status(result) must equalTo(OK)
  }
}
