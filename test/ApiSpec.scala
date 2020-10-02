import agent.{Collector, CollectorAgent, CollectorAgentTrait, Datum, IndexedItem, Label, Origin, ResourceType}
import akka.actor.ActorSystem
import collectors.Instance
import conf.PrismConfiguration
import controllers.{Api, ApiCallException, ApiResult, Prism}
import play.api.Configuration
import play.api.libs.json._
import play.api.libs.json.JsArray
import play.api.mvc._
import play.api.test._
import play.api.test.Helpers._
import play.mvc.Controller

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 * For more information, consult the wiki.
 */
object ApiSpec extends PlaySpecification with Results {
//  "ApiResult" should {
//    "wrap data with status on a successful response" in {
//      implicit val request: FakeRequest[AnyContentAsEmpty.type] = FakeRequest(GET, "/test")
//
//      val components = Helpers.stubControllerComponents()
//      val success = ApiResult.noSource({
//        Json.obj("test" -> "value")
//      })(components.executionContext)
//
//      contentType(success) must beSome("application/json")
//      status(success) must equalTo(OK)
//      (contentAsJson(success) \ "status").get mustEqual JsString("success")
//    }
//
//    "wrap data with fail when an Api exception is thrown" in {
//      implicit val request = FakeRequest(GET, "/test")
//      val components = Helpers.stubControllerComponents()
//      val fail = ApiResult.noSource({
//        if (true) throw ApiCallException(Json.obj("test" -> "just testing the fail state"))
//        Json.obj("never" -> "reached")
//      })(components.executionContext)
//      contentType(fail) must beSome("application/json")
//      status(fail) must equalTo(BAD_REQUEST)
//      (contentAsJson(fail) \ "status").get mustEqual JsString("fail")
//      (contentAsJson(fail) \ "data").get mustEqual Json.obj("test" -> "just testing the fail state")
//    }
//
//    "return an error when something else goes wrong" in {
//      implicit val request = FakeRequest(GET, "/test")
//      val components = Helpers.stubControllerComponents()
//      val error = ApiResult.noSource({
//        Json.obj("infinity" -> (1 / 0))
//      })(components.executionContext)
//      contentType(error) must beSome("application/json")
//      status(error) must equalTo(INTERNAL_SERVER_ERROR)
//      (contentAsJson(error) \ "status").get mustEqual JsString("error")
//      (contentAsJson(error) \ "message").get mustEqual JsString("/ by zero")
//    }
//
//    "add a length companion field to arrays contained in objects when requested" in {
//      implicit val request = FakeRequest(GET, "/test?_length=true")
//      val components = Helpers.stubControllerComponents()
//      val success = ApiResult.noSource({
//        Json.obj("test" -> List("first", "second", "third"))
//      })(components.executionContext)
//      (contentAsJson(success) \ "data" \ "test.length").get mustEqual JsNumber(3)
//    }
//  }

//  class TestApi() extends Controller with Api

  case class TestItem(arn: String, name: String, region: String) extends IndexedItem {
    def callFromArn: String => Call = arn => Call("GET", "localhost")
  }

  object TestItem {
    implicit val testItemWrites = Json.writes[TestItem]
  }

  class TestOrigin extends Origin {
    def vendor: String = "vendor"
    def account: String = "account"
    def resources: Set[String] = Set("resources")
    def jsonFields: Map[String, String] = Map("key" -> "value")
  }


  class TestCollectorAgent extends CollectorAgentTrait[TestItem] {
    private val duration = FiniteDuration(1, "s")
    private val resourceType = ResourceType("test", duration, duration)
    private val label = Label(resourceType, new TestOrigin, 1)

    def get(collector: Collector[TestItem]): Datum[TestItem] = Datum(label, Seq(TestItem("arn", "name", "region")))

    def get(): Iterable[Datum[TestItem]] = Seq(Datum(label, Seq(TestItem("arn", "name", "region"))))

    def getTuples: Iterable[(Label, TestItem)] = Seq((label, TestItem("arn", "name", "region") ))

    def getLabels: Seq[Label] = Seq(label)

    def size: Int = 1

    def update(collector: Collector[TestItem], previous:Datum[TestItem]):Datum[TestItem] = Datum(label, Seq(TestItem("arn", "name", "region")))

    def init():Unit = {}

    def shutdown():Unit = {}
  }

  "Application" should {
    "return a list of instances" in {
      implicit val testItem = TestItem("arn", "name", "region")
      val result: Future[Result] = Api.itemList(
        new TestCollectorAgent(),
        "objectKey",
        ExecutionContext.global
      )(FakeRequest(), TestItem.testItemWrites)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")
      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "objectKey").get
      jsonInstances must beLike { case JsArray(_) => ok }
      jsonInstances.as[JsArray].value.length mustEqual 1
    }

//    "return a list of instances" in new WithApplicationLoader(new PrismApplicationLoader()) {
//      val api = new TestApi()
//      val result = api.instanceList(FakeRequest())
//      status(result) must equalTo(OK)
//      contentType(result) must beSome("application/json")
//      val jsonInstances = (contentAsJson(result) \ "data" \ "instances").get
//      jsonInstances must beLike { case JsArray(_) => ok }
//      jsonInstances.as[JsArray].value.length mustEqual 15
//
//    }




//
//    "filter a list of instances" in new WithApplicationLoader(new PrismApplicationLoader()) {
//      val components: ControllerComponents = Helpers.stubControllerComponents()
//      val prismConfig = new PrismConfiguration(Configuration.empty)
//      val prismContoller = new Prism(prismConfig)(ActorSystem.apply(""))
//      val controller             = new Api(components, prismContoller, components.executionContext, prismConfig)
//
//      val result: Future[Result] = controller.instanceList(FakeRequest(GET, "/instances?vendor=aws"))
//      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "instances").get
//      jsonInstances.as[JsArray].value.length mustEqual 8
//    }
//
//    "invert filter a list of instances" in new WithApplicationLoader(new PrismApplicationLoader()) {
//      val components: ControllerComponents = Helpers.stubControllerComponents()
//      val prismConfig = new PrismConfiguration(Configuration.empty)
//      val prismContoller = new Prism(prismConfig)(ActorSystem.apply(""))
//      val controller             = new Api(components, prismContoller, components.executionContext, prismConfig)
//
//      val result: Future[Result] = controller.instanceList(FakeRequest(GET, "/instances?vendor!=aws"))
//      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "instances").get
//      jsonInstances.as[JsArray].value.length mustEqual 7
//    }
//
//    "filter a list of instances using a regex" in new WithApplicationLoader(new PrismApplicationLoader()) {
//      val components: ControllerComponents = Helpers.stubControllerComponents()
//      val prismConfig = new PrismConfiguration(Configuration.empty)
//      val prismContoller = new Prism(prismConfig)(ActorSystem.apply(""))
//      val controller             = new Api(components, prismContoller, components.executionContext, prismConfig)
//
//      val result: Future[Result] = controller.instanceList(FakeRequest(GET, "/instances?mainclasses~=.*db.*"))
//      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "instances").get
//      jsonInstances.as[JsArray].value.length mustEqual 6
//    }
//
//    "filter a list of instances by nested field" in new WithApplicationLoader(new PrismApplicationLoader()) {
//      val components: ControllerComponents = Helpers.stubControllerComponents()
//      val prismConfig = new PrismConfiguration(Configuration.empty)
//      val prismContoller = new Prism(prismConfig)(ActorSystem.apply(""))
//      val controller             = new Api(components, prismContoller, components.executionContext, prismConfig)
//
//      val result: Future[Result] = controller.instanceList(FakeRequest(GET, "/instances?tags.App=db"))
//      //contentAsString(result) mustEqual("")
//      val jsonInstances: JsValue = (contentAsJson(result) \ "data" \ "instances").get
//      jsonInstances.as[JsArray].value.length mustEqual 3
//    }
  }
}
