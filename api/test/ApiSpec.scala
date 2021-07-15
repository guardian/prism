import agent._
import controllers.{Api, ApiCallException, ApiResult}
import org.joda.time.DateTime
import play.api.libs.json.{JsArray, _}
import play.api.mvc._
import play.api.test._

import scala.concurrent.{ExecutionContext, Future}

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
object ApiSpec extends PlaySpecification with Results {
  "ApiResult" should {
    "wrap data with status on a successful response" in {
      implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/test")

      val components = Helpers.stubControllerComponents()
      implicit val executionContext: ExecutionContext = components.executionContext

      val success = ApiResult.noSource({
        Json.obj("test" -> "value")
      })

      contentType(success) must beSome("application/json")
      status(success) must equalTo(OK)
      (contentAsJson(success) \ "status").get mustEqual JsString("success")
    }

    "wrap data with fail when an Api exception is thrown" in {
      implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/test")
      val components = Helpers.stubControllerComponents()
      implicit val executionContext: ExecutionContext = components.executionContext
      val fail = ApiResult.noSource({
        if (true) throw ApiCallException(Json.obj("test" -> "just testing the fail state"))
        Json.obj("never" -> "reached")
      })
      contentType(fail) must beSome("application/json")
      status(fail) must equalTo(BAD_REQUEST)
      (contentAsJson(fail) \ "status").get mustEqual JsString("fail")
      (contentAsJson(fail) \ "data").get mustEqual Json.obj("test" -> "just testing the fail state")
    }

    "return an error when something else goes wrong" in {
      implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/test")
      val components = Helpers.stubControllerComponents()
      implicit val executionContext: ExecutionContext = components.executionContext
      val error = ApiResult.noSource({
        Json.obj("infinity" -> (1 / 0))
      })
      contentType(error) must beSome("application/json")
      status(error) must equalTo(INTERNAL_SERVER_ERROR)
      (contentAsJson(error) \ "status").get mustEqual JsString("error")
      (contentAsJson(error) \ "message").get mustEqual JsString("/ by zero")
    }

    "add a length companion field to arrays contained in objects when requested" in {
      implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/test?_length=true")
      val components = Helpers.stubControllerComponents()
      implicit val executionContext: ExecutionContext = components.executionContext
      val success = ApiResult.noSource({
        Json.obj("test" -> List("first", "second", "third"))
      })
      (contentAsJson(success) \ "data" \ "test.length").get mustEqual JsNumber(3)
    }
  }

  case class TestItem(arn: String, name: String, region: String, tags: Map[String, String] = Map.empty) extends IndexedItem {
    def callFromArn: String => Call = _ => Call("GET", "localhost")
  }

  object TestItem {
    implicit val testItemWrites: OWrites[TestItem] = Json.writes[TestItem]
  }

  val TestOrigin = ApiOrigin(
    vendor = "vendor",
    accountName = "account",
    Map.empty,
    JsObject.empty
  )

  class TestCollectorAgent extends CollectorAgentTrait[TestItem] {
    private val resourceType = ResourceType("test")
    private val label = ApiLabel(resourceType.name, TestOrigin, 1, DateTime.now, true, Some(1), Label.SUCCESS, None, Nil)

    def get(): Iterable[ApiDatum[TestItem]] = Seq(ApiDatum(label, Seq(TestItem("arn", "name", "eu-west-1", Map("stage" -> "PROD")), TestItem("arn", "name", "eu-west-1", Map("stage" -> "PROD")), TestItem("arn", "name", "eu-west-2", Map("stage" -> "CODE")))))

    def init():Unit = {}

    def shutdown():Unit = {}
  }

  "Application" should {
    "return a list of instances" in {
      val result: Future[Result] = Api.itemList(
        new TestCollectorAgent(),
        "objectKey"
      )(FakeRequest(), TestItem.testItemWrites, ExecutionContext.global)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")
      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "objectKey").get
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual 3
    }

    "filter a list of instances" in {
      val result: Future[Result] = Api.itemList(
        new TestCollectorAgent(),
        "objectKey",
      )(FakeRequest(GET, "/objectKey?region=eu-west-1"), TestItem.testItemWrites, ExecutionContext.global)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")
      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "objectKey").get
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual 2
    }

    "invert filter a list of instances" in {
      val result: Future[Result] = Api.itemList(
        new TestCollectorAgent(),
        "objectKey",
      )(FakeRequest(GET, "/objectKey?region!=eu-west-1"), TestItem.testItemWrites, ExecutionContext.global)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")
      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "objectKey").get
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual 1
    }

    "filter a list of instances using a regex" in {
      val result: Future[Result] = Api.itemList(
        new TestCollectorAgent(),
        "objectKey",
      )(FakeRequest(GET, "/objectKey?region~=.*1"), TestItem.testItemWrites, ExecutionContext.global)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")
      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "objectKey").get
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual 2
    }

    "filter a list of instances by nested field" in {
      val result: Future[Result] = Api.itemList(
        new TestCollectorAgent(),
        "objectKey",
      )(FakeRequest(GET, "/objectKey?tags.stage=PROD"), TestItem.testItemWrites, ExecutionContext.global)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")
      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "objectKey").get
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual 2
    }
  }
}
