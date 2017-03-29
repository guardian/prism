package controllers

import agent.{Label, Origin, ResourceType}
import data.Owners
import jsonimplicits.model._
import model.Owner
import org.joda.time.{DateTime, Duration}
import play.api.libs.json.{Json, Writes}
import play.api.libs.json.Json._
import play.api.mvc.{Action, RequestHeader, Result}
import utils.ResourceFilter
import play.api.http.Status._

import scala.concurrent.Future

object OwnerApi {

  private def itemsResult[T](key: String, items: => Iterable[T])(implicit request: RequestHeader, tw: Writes[T]): Future[Result] = {
    ApiResult.filter {
      Map(ownerLabel -> items.toSeq)
    } reduce { collection =>
      Json.obj(key -> toJson(collection.values.flatten))
    }
  }

  object OwnerResourceType extends ResourceType("Owners", Duration.standardDays(30))

  val ownerLabel = Label(
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

  def ownerList = Action.async { implicit request =>
    val requestFilter =  ResourceFilter.fromRequestWithDefaults()
    def filter(owner: Owner) = {
      val json = Json.toJson(owner)
      if (requestFilter.isMatch(json)) Some(json) else None
    }
    itemsResult("owners", Owners.all.toSeq.flatMap(filter))
  }

  def ownerForStack = Action.async { implicit request =>
    val stack: Option[String] = request.getQueryString("stack")
    val stage: Option[String] = request.getQueryString("stage")
    val app: Option[String] = request.getQueryString("app")
    itemsResult("owner", Owners.forStack(stack, stage, app))
  }

  def owner(id:String) = Action.async { implicit request =>
    val owner = Owners
      .find(id)
      .orElse(throw ApiCallException(Json.obj("id" -> s"Owner with id '$id' doesn't exist"), NOT_FOUND))
    itemsResult("owner", owner)
  }

}
