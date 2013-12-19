package controllers

import play.api.mvc._
import play.api.libs.json._
import deployinfo.{DeployInfo, DeployInfoManager}
import play.api.http.Status
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.duration.Duration
import play.api.libs.json.Json._
import model.DataContainer
import org.joda.time.DateTime
import collectors._
import scala.util.Try
import scala.language.postfixOps
import utils.{ResourceFilter, Matchable, Logging}
import jsonimplicits.joda._

// use this when the API call has illegal parameters
case class IllegalApiCallException(failure:JsObject, status:Int = Status.BAD_REQUEST)
  extends RuntimeException(failure.fields.map(f => s"${f._1}: ${f._2}").mkString("; "))

object ApiResult extends Logging {
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

  object mr {
    import jsonimplicits.model.labelWriter

    def apply[D](mapSources: => Map[Label, Seq[D]])(reduce: Map[Label, Seq[D]] => JsValue)(implicit request:RequestHeader): Future[SimpleResult] = {
      async[D](mapSources)(sources => Future.successful(reduce(sources)))
    }
    def async[D](mapSources: => Map[Label, Seq[D]])(reduce: Map[Label, Seq[D]] => Future[JsValue])(implicit request:RequestHeader): Future[SimpleResult] = {
      Try {
        val filter = ResourceFilter.fromRequest
        val filteredSources = mapSources.groupBy{ case (label, data) => filter.isMatch(label.origin.filterMap) }
        filteredSources.get(false).map(falseMap => if (falseMap.values.exists(_.size == 0)) log.warn("The origin filter contract map has been violated: data exists in a discarded source"))

        val sources:Map[Label, Seq[D]] = filteredSources.getOrElse(true, Map.empty)

        val usedLabels = sources.filter {
          case (_,data) => !data.isEmpty
        }.keys

        val staleLabels = sources.keys.filter { label => label.bestBefore.isStale }

        val lastUpdated: DateTime = usedLabels.toSeq.filterNot(_.isError).map(_.createdAt) match {
          case dates:Seq[DateTime] if !dates.isEmpty => dates.min(new Ordering[DateTime] {
            def compare(x: DateTime, y: DateTime): Int = x.getMillis.compareTo(y.getMillis)
          })
          case _ => new DateTime(0)
        }

        val stale = sources.keys.exists(_.bestBefore.isStale)

        reduce(sources).map { data =>
          val dataWithMods = if (request.getQueryString("_length").isDefined) addCountToJson(data) else data
          Results.Ok(Json.obj(
                "status" -> "success",
                "lastUpdated" -> lastUpdated,
                "stale" -> stale,
                "staleSources" -> staleLabels,
                "data" -> dataWithMods,
                "sources" -> usedLabels
              ))
        }
      } recover {
        case IllegalApiCallException(failure, status) =>
          Future.successful(Results.Status(status)(Json.obj(
            "status" -> "fail",
            "data" -> failure
          )))
        case e:Exception =>
          Future.successful(Results.InternalServerError(Json.obj(
            "status" -> "error",
            "message" -> e.getMessage,
            "stacktrace" -> e.getStackTrace.map(_.toString)
          )))
      } get
    }
  }

  def apply[T <: DataContainer](source: T)(block: T => JsValue)(implicit request:RequestHeader): SimpleResult =
    Await.result(ApiResult.async(source)(source => Future.successful(block(source))), Duration.Inf)
  def noSource(block: => JsValue)(implicit request:RequestHeader): SimpleResult = apply(noSourceContainer)(_ => block)

  object async {
    def apply[T <: DataContainer](source: T)(block: T => Future[JsValue])(implicit request:RequestHeader): Future[SimpleResult] = {
      val sourceLabel:Label = Label(
        Resource(source.name, org.joda.time.Duration.standardMinutes(15)),
        new Origin {
          val account = "unknown"
          val vendor = "unknown"
          val resources = Set.empty[String]
        },
        source.lastUpdated
      )
      mr.async(Map(sourceLabel -> Seq("dummy"))){ emptyMap =>
        block(source)
      }
    }
    def noSource(block: => Future[JsValue])(implicit request:RequestHeader): Future[SimpleResult] = async(noSourceContainer)(_ => block)
  }
}

object Api extends Controller with Logging {

  import jsonimplicits.model._

  def sortString(jsv: JsValue):String =
    jsv match {
      case JsString(str) => str
      case JsArray(seq) => seq.map(sortString).mkString
      case JsObject(fields) => fields.map{case(key, value) => s"${key}${sortString(value)}"}.mkString
      case _ => ""
    }


  def summary[T](sourceAgent: CollectorAgent[T], transform: T => Iterable[JsValue], key: String, enableFilter: Boolean = false)(implicit ordering:Ordering[String]) =
    Action.async { implicit request =>
      ApiResult.mr[JsValue] {
        sourceAgent.get().map { datum => datum.label -> datum.data.flatMap(transform)}.toMap
      } { transformed =>
        val objects = transformed.values.toSeq.flatten.distinct.sortBy(sortString)(ordering)
        val filteredObjects = if (enableFilter) {
          val filter = ResourceFilter.fromRequest
          objects.filter(filter.isMatch)
        } else objects
        Json.obj(key -> Json.toJson(filteredObjects))
      }
    }

  def sources = Action.async { implicit request =>
    ApiResult.mr[SourceStatus] {
      val sources = CollectorAgent.sources
      Map(sources.label -> sources.data)
    } { collection =>
      toJson(collection.map(_._2).flatten)
    }
  }

  def instanceJson(instance: Instance, expand: Boolean = false, filter: Matchable[JsValue] = ResourceFilter.all)(implicit request: RequestHeader): Option[JsValue] = {
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

  def instanceList = Action.async { implicit request =>
    ApiResult.mr {
      val expand = request.getQueryString("_expand").isDefined
      val filter = ResourceFilter.fromRequestWithDefaults("vendorState" -> "running", "vendorState" -> "ACTIVE")
      Prism.instanceAgent.get().map { agent => agent.label -> agent.data.flatMap(host => instanceJson(host, expand, filter)) }.toMap
    } { collection =>
      Json.obj(
        "instances" -> toJson(collection.values.flatten)
      )
    }
  }

  def instance(id:String) = Action.async { implicit request =>
    ApiResult.mr {
      val sources = Prism.instanceAgent.get()
      sources.flatMap{ datum =>
        datum.data.find(_.id == id).map(datum.label -> Seq(_))
      }.toMap
    } { sources =>
      instanceJson(sources.values.flatten.head, true).get
    }
  }

  def hardwareList = Action.async { implicit request =>
    ApiResult.mr {
      val requestFilter = ResourceFilter.fromRequest
      Prism.hardwareAgent.get().map { agent =>
        agent.label -> agent.data.map(hardware => Json.toJson(hardware)).filter(json => requestFilter.isMatch(json))
      }.toMap
    } { sources =>
      Json.obj("hardware" -> toJson(sources.values.flatten))
    }
  }

  // an empty endpoint simply for getting metadata from
  def empty = Action { implicit request => DeployApiResult { di => Json.obj() }}

  def roleList = summary[Instance](Prism.instanceAgent, i => i.role.map(Json.toJson(_)), "roles")
  def mainclassList = summary[Instance](Prism.instanceAgent, i => i.mainclasses.map(Json.toJson(_)), "mainclasses")
  def stackList = summary[Instance](Prism.instanceAgent, i => i.stack.map(Json.toJson(_)), "stacks")
  def stageList =
    summary[Instance](Prism.instanceAgent, i => i.stage.map(Json.toJson(_)), "stages")(conf.Configuration.stages.ordering)
  def regionList = summary[Instance](Prism.instanceAgent, i => Some(Json.toJson(i.region)), "regions")
  def accountNameList = summary[Instance](Prism.instanceAgent, i => Some(Json.toJson(i.accountName)), "accountNames")
  def vendorList = summary[Instance](Prism.instanceAgent, i => Some(Json.toJson(i.vendor)), "regions")
  def appList = summary[Instance](
    Prism.instanceAgent,
    i => i.apps.flatMap{ app => i.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app))) },
    "apps",
    enableFilter = true
  )

  def DeployApiResult(block: DeployInfo => JsValue)(implicit request:RequestHeader) = ApiResult(DeployInfoManager.deployInfo)(block)

  def dataList = Action { implicit request =>
    DeployApiResult { implicit di =>
      Json.obj("data" -> DeployInfoManager.deployInfo.data)
    }
  }

  def dataKeysList = Action { implicit request =>
    DeployApiResult { implicit di =>
      Json.obj("keys" -> DeployInfoManager.deployInfo.data.keys)
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