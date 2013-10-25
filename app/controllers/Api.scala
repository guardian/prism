package controllers

import play.api.mvc._
import play.api.libs.json._
import deployinfo.{DeployInfo, Data, Host, DeployInfoManager}
import play.api.http.Status
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration.Duration
import scala.util.matching.Regex
import play.api.libs.json.Json._
import model.DataContainer
import org.joda.time.DateTime
import utils.Json.DefaultJodaDateWrites

// use this when a
case class IllegalApiCallException(failure:JsObject, status:Int = Status.BAD_REQUEST)
  extends RuntimeException(failure.fields.map(f => s"${f._1}: ${f._2}").mkString("; "))

object ApiResult {
  val noSourceContainer = new DataContainer {
    val name = "no data source"
    def lastUpdated: DateTime = new DateTime
    val isStale = false
  }

  def addCountToJson(data: JsValue):JsValue = {
    data match {
      case JsObject(fields) =>
        JsObject(fields.flatMap { case (key, value) =>
          value match {
            case JsArray(array) =>
              List((s"$key.length",JsNumber(array.size)), (key, addCountToJson(value)))
            case _ => List((key, addCountToJson(value)))
          }
        })
      case JsArray(values) =>
        JsArray(values.map(addCountToJson))
      case other => other
    }
  }

  def apply[T <: DataContainer](source: T)(block: T => JsValue): SimpleResult =
    Await.result(ApiResult.async(source)(source => Future.successful(block(source))), Duration.Inf)
  def noSource(block: => JsValue): SimpleResult = apply(noSourceContainer)(_ => block)

  object async {
    def apply[T <: DataContainer](source: T)(block: T => Future[JsValue]): Future[SimpleResult] = {
      try {
        block(source).map { data =>
          val dataWithCounts = addCountToJson(data)
          Results.Ok(Json.obj(
            "status" -> "success",
            "sources" -> Seq(source.name),
            "lastUpdated" -> source.lastUpdated,
            "stale" -> source.isStale,
            "data" -> dataWithCounts
          ))
        }
      } catch {
        case IllegalApiCallException(failure, status) =>
          Future.successful(Results.Status(status)(Json.obj(
            "status" -> "fail",
            "data" -> failure
          )))
        case e:Exception =>
          Future.successful(Results.InternalServerError(Json.obj(
            "status" -> "error",
            "message" -> e.getMessage
          )))
      }
    }
    def noSource(block: => Future[JsValue]): Future[SimpleResult] = async(noSourceContainer)(_ => block)
  }
}

object Api extends Controller {

  implicit val instanceWriter = Json.writes[Host]
  implicit val dataWriter = Json.writes[Data]

  trait Matchable[T] {
    def isMatch(value: T): Boolean
  }
  case class StringMatchable(matcher: String) extends Matchable[String] {
    def isMatch(value: String): Boolean = value == matcher
  }
  case class RegExMatchable(matcher: Regex) extends Matchable[String] {
    def isMatch(value: String): Boolean = matcher.unapplySeq(value).isDefined
  }
  case class Filter(filter:Map[String,Seq[Matchable[String]]]) extends Matchable[JsValue] {
    def isMatch(json: JsValue): Boolean = {
      filter.map { case (field, values) =>
        val value = json \ field
        value match {
          case JsString(str) => values exists (_.isMatch(str))
          case JsNumber(int) => values exists (_.isMatch(int.toString))
          case JsArray(seq) =>
            seq.exists {
              case JsString(str) => values exists (_.isMatch(str))
              case _ => false
            }
          case _ => false
        }

      } forall(ok => ok)
    }
  }
  object Filter {
    def fromRequest(implicit request: RequestHeader): Filter = {
      val matcher = (value:String) => request.getQueryString("_match") match {
        case Some("regex") => RegExMatchable(value.r)
        case _ => StringMatchable(value)
      }
      val filterKeys = request.queryString.filterKeys(!_.startsWith("_")).mapValues {valueList => valueList.map(matcher)}
      Filter(filterKeys)
    }
    lazy val all = new Matchable[JsValue] {
      def isMatch(value: JsValue): Boolean = true
    }
  }

  def instanceJson(instance: Host, expand: Boolean = false, filter: Matchable[JsValue] = Filter.all)(implicit request: RequestHeader): Option[JsValue] = {
    val json = Json.toJson(instance).as[JsObject]
    if (filter.isMatch(json)) {
      val filtered = if (expand) json else JsObject(json.fields.filter(List("id") contains _._1))
      Some(filtered ++ Json.obj("meta"-> Json.obj(
        "href" -> routes.Api.instance(instance.id).absoluteURL()
      )))
    } else {
      None
    }
  }

  def DeployApiResult = ApiResult(DeployInfoManager.deployInfo) _

  def instanceList = Action { implicit request =>
    DeployApiResult { di =>
      val expand = request.getQueryString("_expand").isDefined
      val filter = Filter.fromRequest
      Json.obj(
        "instances" -> di.hosts.flatMap(host => instanceJson(host, expand, filter))
      )
    }
  }
  def instance(id:String) = Action { implicit request =>
    DeployApiResult { di =>
      val instance = di.hosts.find(_.id == id).getOrElse(throw new IllegalApiCallException(Json.obj("id" -> "unknown ID")))
      instanceJson(instance, true).get
    }
  }

  def instanceSummary(transform: Host => Seq[JsValue])(implicit di:DeployInfo) = {
    def sortString(jsv: JsValue):String =
      jsv match {
        case JsString(str) => str
        case JsArray(seq) => seq.map(sortString).mkString
        case JsObject(fields) => fields.map{case(key, value) => s"${key}${sortString(value)}"}.mkString
        case _ => ""
      }

    di.hosts.flatMap(transform).distinct.sortBy(sortString)
  }

  def roleList = Action { implicit request =>
    DeployApiResult { implicit di => Json.obj( "roles" -> instanceSummary(host => Seq(Json.toJson(host.role))) ) }
  }
  def mainclassList = Action { implicit request =>
    DeployApiResult { implicit di => Json.obj("mainclasses" -> instanceSummary(host => host.mainclasses.map(Json.toJson(_)))) }
  }
  def stackList = Action { implicit request =>
    DeployApiResult { implicit di => Json.obj("stacks" -> instanceSummary(host => host.stack.map(Json.toJson(_)).toSeq)) }
  }
  def stageList = Action { implicit request =>
    DeployApiResult { implicit di => Json.obj("stages" -> instanceSummary(host => Seq(Json.toJson(host.stage)))) }
  }
  def regionList = Action { implicit request =>
    DeployApiResult { implicit di => Json.obj("regions" -> instanceSummary(host => Seq(Json.toJson(host.region)))) }
  }
  def accountList = Action { implicit request =>
    DeployApiResult { implicit di => Json.obj("accounts" -> instanceSummary(host => Seq(Json.toJson(host.account)))) }
  }
  def vendorList = Action { implicit request =>
    DeployApiResult { implicit di => Json.obj("vendors" -> instanceSummary(host => Seq(Json.toJson(host.vendor)))) }
  }

  def appList = Action { implicit request =>
    val filter = Filter.fromRequest
    DeployApiResult { implicit di =>
      val apps = instanceSummary{ host =>
        host.apps.flatMap{ app =>
          host.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app))).filter(filter.isMatch)
        }.toSeq
      }
      Json.obj(
        "apps" -> apps
      )
    }
  }

  def dataList = Action { implicit request =>
    DeployApiResult { implicit di =>
      Json.obj("data" -> DeployInfoManager.deployInfo.data)
    }
  }

  def dataLookup(key:String) = Action { implicit request =>
    DeployApiResult { di =>
      val app = request.getQueryString("app")
      val stage = request.getQueryString("stage")
      val validKey = DeployInfoManager.deployInfo.knownKeys.contains(key)

      val errors:Map[String,String] = Map.empty ++
        (if (app.isEmpty) Some("app" -> "Must specify app") else None) ++
        (if (stage.isEmpty) Some("stage" -> "Must specify stage") else None) ++
        (if (validKey) None else Some("key" -> s"The key name $key was not found"))
      if (!errors.isEmpty) throw IllegalApiCallException(Json.toJson(errors).as[JsObject])

      Json.toJson(di.firstMatchingData(key, app.get, stage.get))
    }
  }
}