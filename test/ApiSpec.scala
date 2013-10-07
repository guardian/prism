import org.specs2.mutable._
import org.specs2.runner._
import org.junit.runner._

import play.api.libs.json.{JsArray, JsObject}
import play.api.test._
import play.api.test.Helpers._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
@RunWith(classOf[JUnitRunner])
class ApiSpec extends Specification {
  "Application" should {
    "send 404 on a bad request" in new WithApplication{
      route(FakeRequest(GET, "/boum")) must beNone
    }

    "return a list of instances" in new WithApplication{
      val home = route(FakeRequest(GET, "/instance")).get

      status(home) must equalTo(OK)
      contentType(home) must beSome("application/json")
      val jsonInstances = contentAsJson(home) \ "instances"
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual(56)
    }
  }
}
