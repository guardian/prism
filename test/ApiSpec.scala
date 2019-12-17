import controllers.{Api, ApiCallException, ApiResult}

import play.api.libs.json._
import play.api.libs.json.JsArray
import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._
import scala.concurrent.Future

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
object ApiSpec extends PlaySpecification with Results {
  "ApiResult" should {
    "wrap data with status on a successful response" in {
      implicit val request = FakeRequest(GET, "/test")
      val success = ApiResult.noSource {
        Json.obj("test" -> "value")
      }
      contentType(success) must beSome("application/json")
      status(success) must equalTo(OK)
      (contentAsJson(success) \ "status").get mustEqual JsString("success")
    }

    "wrap data with fail when an Api exception is thrown" in {
      implicit val request = FakeRequest(GET, "/test")
      val fail = ApiResult.noSource {
        if (true) throw ApiCallException(Json.obj("test" -> "just testing the fail state"))
        Json.obj("never" -> "reached")
      }
      contentType(fail) must beSome("application/json")
      status(fail) must equalTo(BAD_REQUEST)
      (contentAsJson(fail) \ "status").get mustEqual JsString("fail")
      (contentAsJson(fail) \ "data").get mustEqual Json.obj("test" -> "just testing the fail state")
    }

    "return an error when something else goes wrong" in {
      implicit val request = FakeRequest(GET, "/test")
      val error = ApiResult.noSource {
        Json.obj("infinity" -> (1 / 0))
      }
      contentType(error) must beSome("application/json")
      status(error) must equalTo(INTERNAL_SERVER_ERROR)
      (contentAsJson(error) \ "status").get mustEqual JsString("error")
      (contentAsJson(error) \ "message").get mustEqual JsString("/ by zero")
    }

    "add a length companion field to arrays contained in objects when requested" in {
      implicit val request = FakeRequest(GET, "/test?_length=true")
      val success = ApiResult.noSource {
        Json.obj("test" -> List("first", "second", "third"))
      }
      (contentAsJson(success) \ "data" \ "test.length").get mustEqual JsNumber(3)
    }
  }

  class TestApi() extends Controller

  "Application" should {
    "return a list of instances" in new WithApplicationLoader(new PrismApplicationLoader()) {
//      val api = new TestApi()
//      val result = api.instanceList(FakeRequest())
      val result = route(app, FakeRequest(GET, "/instances")).get
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")
      val jsonInstances = (contentAsJson(result) \ "data" \ "instances").get
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual 15

    }

    "filter a list of instances" in new WithApplicationLoader(new PrismApplicationLoader()) {
      val api = new TestApi()
//      val result = api.instanceList(FakeRequest(GET, "/instances?vendor=aws"))
      val result = route(app, (FakeRequest(GET, "/instances?vendor=aws"))).get
      val jsonInstances = (contentAsJson(result) \ "data" \ "instances").get
      jsonInstances.as[JsArray].value.length mustEqual 8
    }

    "invert filter a list of instances" in new WithApplicationLoader(new PrismApplicationLoader()) {
      val api = new TestApi()
      val result = route(app, FakeRequest(GET, "/instances?vendor!=aws")).get
      val jsonInstances = (contentAsJson(result) \ "data" \ "instances").get
      jsonInstances.as[JsArray].value.length mustEqual 7
    }

    "filter a list of instances using a regex" in new WithApplicationLoader(new PrismApplicationLoader()) {
      val api = new TestApi()
      val result = route(app, FakeRequest(GET, "/instances?mainclasses~=.*db.*")).get
      val jsonInstances = (contentAsJson(result) \ "data" \ "instances").get
      jsonInstances.as[JsArray].value.length mustEqual 6
    }

    "filter a list of instances by nested field" in new WithApplicationLoader(new PrismApplicationLoader()) {
      val api = new TestApi()
      val result = route(app, (FakeRequest(GET, "/instances?tags.App=db"))).get
      //contentAsString(result) mustEqual("")
      val jsonInstances = (contentAsJson(result) \ "data" \ "instances").get
      jsonInstances.as[JsArray].value.length mustEqual 3
    }
  }
}
