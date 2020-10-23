package controllers

import agent.{CrawlRate, Label, Origin, ResourceType}
import data.Owners
import jsonimplicits.model._
import model.Owner
import org.joda.time.DateTime
import play.api.mvc._
import play.api.mvc.{RequestHeader, Result}
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.libs.json.Json._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import utils.ResourceFilter

//noinspection TypeAnnotation
class OwnerApi(cc: ControllerComponents)(implicit executionContext: ExecutionContext) extends AbstractController(cc) {

    private def itemsResult[T](key: String, items: => Iterable[T], baseObject: JsObject = Json.obj())(implicit request: RequestHeader, tw: Writes[T]): Future[Result] = {
      ApiResult.filter {
        Map(ownerLabel -> items.toSeq)
      } reduce { collection =>
        baseObject ++ Json.obj(key -> toJson(collection.values.flatten))
      }
    }

    object OwnerResourceType extends ResourceType("Owners")

    val ownerLabel: Label = Label(
      OwnerResourceType,
      new Origin {
        val vendor = "prism"
        val account = "prism"
        val resources = Set("sources")
        val jsonFields = Map.empty[String, String]
        val crawlRate = Map("Owners" -> CrawlRate(30 days, 30 days))
      },
      Owners.all.size,
      DateTime.now
    )

    def ownerList = Action.async { implicit request =>
      val requestFilter =  ResourceFilter.fromRequestWithDefaults()
      def filter(owner: Owner) = {
        val json = Json.toJson(owner)
        Some(json).filter(requestFilter.isMatch)
      }
      itemsResult("owners", Owners.all.toSeq.flatMap(filter), Json.obj("defaultOwner" -> Owners.default))
    }

    def ownerForStack(stack: String) = Action.async { implicit request =>
      val stage: Option[String] = request.getQueryString("stage")
      val app: Option[String] = request.getQueryString("app")
      val owner = Owners.forStack(stack, stage, app)
      itemsResult("owner", Some(owner))
    }

  def owner(id: String) = Action.async { implicit request =>
        val owner = Owners
          .find(id)
          .orElse(throw ApiCallException(Json.obj("id" -> s"Owner with id '$id' doesn't exist"), NOT_FOUND))
        itemsResult("owner", owner)
      }
}