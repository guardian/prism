package controllers

import agent.{Label, Origin, ResourceType}
import data.Owners
import javax.inject.Inject
import jsonimplicits.model._
import model.Owner
import org.joda.time.DateTime
import play.api.mvc._
import play.api.mvc.{Action, RequestHeader, Result}
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.libs.json.Json._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import utils.ResourceFilter

class OwnerApi @Inject()(cc: ControllerComponents, ec: ExecutionContext) extends AbstractController(cc) {

    private def itemsResult[T](key: String, items: => Iterable[T], baseObject: JsObject = Json.obj())(implicit request: RequestHeader, tw: Writes[T]): Future[Result] = {
      ApiResult.filter {
        Map(ownerLabel -> items.toSeq)
      } reduce({ collection =>
        baseObject ++ Json.obj(key -> toJson(collection.values.flatten))
      }, ec)
    }

    object OwnerResourceType extends ResourceType("Owners", 30 days, 30 days)

    val ownerLabel: Label = Label(
      OwnerResourceType,
      new Origin {
        val vendor = "prism"
        val account = "prism"
        val resources = Set("sources")
        val jsonFields = Map.empty[String, String]
      },
      Owners.all.size,
      DateTime.now
    )

    def ownerList: Action[AnyContent] = Action.async { implicit request =>
      val requestFilter =  ResourceFilter.fromRequestWithDefaults()
      def filter(owner: Owner) = {
        val json = Json.toJson(owner)
        Some(json).filter(requestFilter.isMatch)
      }
      itemsResult("owners", Owners.all.toSeq.flatMap(filter), Json.obj("defaultOwner" -> Owners.default))
    }

    def ownerForStack(stack: String): Action[AnyContent] = Action.async { implicit request =>
      val stage: Option[String] = request.getQueryString("stage")
      val app: Option[String] = request.getQueryString("app")
      val owner = Owners.forStack(stack, stage, app)
      itemsResult("owner", Some(owner))
    }

  def owner(id: String): Action[AnyContent] = Action.async { implicit request =>
        val owner = Owners
          .find(id)
          .orElse(throw ApiCallException(Json.obj("id" -> s"Owner with id '$id' doesn't exist"), NOT_FOUND))
        itemsResult("owner", owner)
      }
}