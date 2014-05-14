package controllers

import play.api.mvc._
import play.api.libs.json._
import play.api.http.{ContentTypes, Status}
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
case class ApiCallException(failure:JsObject, status:Int = Status.BAD_REQUEST)
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

  object filter {

    import jsonimplicits.model.labelWriter

    case class SourceData[D](data: Try[Map[Label, Seq[D]]]) {
      def reduce(reduce: Map[Label, Seq[D]] => JsValue)(implicit request: RequestHeader): Future[SimpleResult] =
        reduceAsync(input => Future.successful(reduce(input)))(request)

      def reduceAsync(reduce: Map[Label, Seq[D]] => Future[JsValue])(implicit request: RequestHeader): Future[SimpleResult] = {
        data.map {
          mapSources =>
            val filter = ResourceFilter.fromRequest
            val filteredSources = mapSources.groupBy {
              case (label, data) => filter.isMatch(label.origin.filterMap)
            }
            filteredSources.get(false).map(falseMap => if (falseMap.values.exists(_.size == 0)) log.warn(s"The origin filter contract map has been violated: data exists in a discarded source - ${request.uri} from ${request.remoteAddress}"))

            val sources: Map[Label, Seq[D]] = filteredSources.getOrElse(true, Map.empty)

            val usedLabels = sources.filter {
              case (_, data) => !data.isEmpty
            }.keys

            val staleLabels = sources.keys.filter {
              label => label.bestBefore.isStale
            }

            val lastUpdated: DateTime = usedLabels.toSeq.filterNot(_.isError).map(_.createdAt) match {
              case dates: Seq[DateTime] if !dates.isEmpty => dates.min(new Ordering[DateTime] {
                def compare(x: DateTime, y: DateTime): Int = x.getMillis.compareTo(y.getMillis)
              })
              case _ => new DateTime(0)
            }

            val stale = sources.keys.exists(_.bestBefore.isStale)

            reduce(sources).map {
              data =>
                val dataWithMods = if (request.getQueryString("_length").isDefined) addCountToJson(data) else data
                val json = Json.obj(
                  "status" -> "success",
                  "lastUpdated" -> lastUpdated,
                  "stale" -> stale,
                  "staleSources" -> staleLabels,
                  "data" -> dataWithMods,
                  "sources" -> usedLabels
                )
                request.getQueryString("_pretty") match {
                  case Some(_) => Results.Ok(Json.prettyPrint(json)).as(ContentTypes.JSON)
                  case None => Results.Ok(json)
                }
            }
        } recover {
          case ApiCallException(failure, status) =>
            Future.successful(Results.Status(status)(Json.obj(
              "status" -> "fail",
              "data" -> failure
            )))
          case e: Exception =>
            Future.successful(Results.InternalServerError(Json.obj(
              "status" -> "error",
              "message" -> e.getMessage,
              "stacktrace" -> e.getStackTrace.map(_.toString)
            )))
        } get
      }
    }

    def apply[D](mapSources: => Map[Label, Seq[D]]): SourceData[D] = {
      new SourceData[D](
        Try {
          mapSources
        }
      )
    }
  }

  def noSource(block: => JsValue)(implicit request:RequestHeader): Future[SimpleResult] = {
    val sourceLabel:Label = Label(
        ResourceType(noSourceContainer.name, org.joda.time.Duration.standardMinutes(15)),
        new Origin {
          val account = "unknown"
          val vendor = "unknown"
          val resources = Set.empty[String]
          val jsonFields = Map.empty[String, String]
        },
        1,
        noSourceContainer.lastUpdated
      )
    filter(Map(sourceLabel -> Seq("dummy"))) reduceAsync { emptyMap =>
      Future.successful(block)
    }
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


  def summary[T<:IndexedItem](sourceAgent: CollectorAgent[T], transform: T => Iterable[JsValue], key: String, enableFilter: Boolean = false)(implicit ordering:Ordering[String]) =
    Action.async { implicit request =>
      ApiResult.filter[JsValue] {
        sourceAgent.get().map { datum => datum.label -> datum.data.flatMap(transform)}.toMap
      } reduce { transformed =>
        val objects = transformed.values.toSeq.flatten.distinct.sortBy(sortString)(ordering)
        val filteredObjects = if (enableFilter) {
          val filter = ResourceFilter.fromRequest
          objects.filter(filter.isMatch)
        } else objects
        Json.obj(key -> Json.toJson(filteredObjects))
      }
    }

  def summaryFromTwo[T<:IndexedItem, U<:IndexedItem](sourceTAgent: CollectorAgent[T],
                                                     transformT: T => Iterable[JsValue],
                                                     sourceUAgent: CollectorAgent[U],
                                                     transformU: U => Iterable[JsValue],
                                                     key: String,
                                                     enableFilter: Boolean = false
                                                      )(implicit ordering:Ordering[String]) =
    Action.async { implicit request =>
      ApiResult.filter[JsValue] {
        sourceTAgent.get().map { datum => datum.label -> datum.data.flatMap(transformT)}.toMap ++
        sourceUAgent.get().map { datum => datum.label -> datum.data.flatMap(transformU)}.toMap
      } reduce { transformed =>
        val objects = transformed.values.toSeq.flatten.distinct.sortBy(sortString)(ordering)
        val filteredObjects = if (enableFilter) {
          val filter = ResourceFilter.fromRequest
          objects.filter(filter.isMatch)
        } else objects
        Json.obj(key -> Json.toJson(filteredObjects))
      }
    }

  def sources = Action.async { implicit request =>
    ApiResult.filter {
      val filter = ResourceFilter.fromRequest
      val sources = CollectorAgent.sources
      Map(sources.label -> sources.data.map(toJson(_)).filter(filter.isMatch))
    } reduce { collection =>
      toJson(collection.map(_._2).flatten)
    }
  }

  def healthCheck = Action.async { implicit request =>
    ApiResult.filter {
      val sources = CollectorAgent.sources
      val notInitialisedSources = sources.data.filter(_.state.status != "success")
      if (notInitialisedSources.isEmpty) Map.empty else Map(sources.label -> notInitialisedSources)
    } reduce { notInitialisedSources =>
      if (notInitialisedSources.isEmpty)
        Json.obj("healthcheck" -> "initialised")
      else
        throw new ApiCallException(Json.obj("healthcheck" -> "not yet initialised", "sources" -> notInitialisedSources.values.headOption), SERVICE_UNAVAILABLE)
    }
  }

  def itemJson[T<:IndexedItem](item: T, expand: Boolean = false, label: Option[Label] = None, filter: Matchable[JsValue] = ResourceFilter.all)(implicit request: RequestHeader, writes: Writes[T]): Option[JsValue] = {
    val json = Json.toJson(item).as[JsObject] ++ Json.obj("meta"-> Json.obj("href" -> item.call.absoluteURL(), "origin" -> label.map(_.origin)))
    if (filter.isMatch(json)) {
      val filtered = if (expand) json else JsObject(json.fields.filter(List("id") contains _._1))
      Some(filtered)
    } else {
      None
    }
  }

  def find = Action.async { implicit request =>
    val filter = ResourceFilter.fromRequest
    ApiResult.filter {
      val sources = Prism.allAgents.map(_.get())
      sources.flatMap{ agent =>
        agent.map{ datum =>
          datum.label -> datum.data.filter(d => filter.isMatch(d.fieldIndex))
        }
      }.toMap
    } reduce { sources =>
      val results = sources.flatMap { case (label, dataItems) =>
        dataItems.map { data =>
          Json.obj(
            "type" -> label.resource.name,
            "href" -> data.call.absoluteURL()
          )
        }
      }
      Json.toJson(results)
    }
  }

  def singleItem[T<:IndexedItem](agent:CollectorAgent[T], id:String)(implicit writes: Writes[T]) =
    Action.async { implicit request =>
      ApiResult.filter {
        val sources = agent.get()
        sources.flatMap{ datum =>
          datum.data.find(_.id == id).map(datum.label -> Seq(_))
        }.toMap
      } reduce { sources =>
        sources.headOption.map {
          case (label, items) =>
            itemJson(items.head, expand = true, label=Some(label)).get
        } getOrElse {
          throw ApiCallException(Json.obj("id" -> s"Item with id $id doesn't exist"), NOT_FOUND)
        } 
      }
    }

  def itemList[T<:IndexedItem](agent:CollectorAgent[T], objectKey:String, defaultFilter: (String,String)*)
                              (implicit writes: Writes[T]) =
    Action.async { implicit request =>
      ApiResult.filter {
        val expand = request.getQueryString("_expand").isDefined
        val filter = ResourceFilter.fromRequestWithDefaults(defaultFilter:_*)
        agent.get().map { agent => agent.label -> agent.data.flatMap(host => itemJson(host, expand, Some(agent.label), filter=filter)) }.toMap
      } reduce { collection =>
        Json.obj(
          objectKey -> toJson(collection.values.flatten)
        )
      }
    }

  def instanceList = itemList(Prism.instanceAgent, "instances", "vendorState" -> "running", "vendorState" -> "ACTIVE")
  def instance(id:String) = singleItem(Prism.instanceAgent, id)

  def hardwareList = itemList(Prism.hardwareAgent, "hardware")
  def hardware(id:String) = singleItem(Prism.hardwareAgent, id)

  def securityGroupList = itemList(Prism.securityGroupAgent, "security-groups")
  def securityGroup(id:String) = singleItem(Prism.securityGroupAgent, id)

  def ownerList = itemList(Prism.ownerAgent, "owners")
  def owner(id:String) = singleItem(Prism.ownerAgent, id)

  def roleList = summary[Instance](Prism.instanceAgent, i => i.role.map(Json.toJson(_)), "roles")
  def mainclassList = summary[Instance](Prism.instanceAgent, i => i.mainclasses.map(Json.toJson(_)), "mainclasses")
  def stackList = summaryFromTwo[Instance, Hardware](
    Prism.instanceAgent, i => i.stack.map(Json.toJson(_)),
    Prism.hardwareAgent, h => h.stack.map(Json.toJson(_)),
    "stacks")
  def stageList = summaryFromTwo[Instance, Hardware](
    Prism.instanceAgent, i => i.stage.map(Json.toJson(_)),
    Prism.hardwareAgent, h => h.stage.map(Json.toJson(_)),
    "stages")(conf.Configuration.stages.ordering)
  def regionList = summary[Instance](Prism.instanceAgent, i => Some(Json.toJson(i.region)), "regions")
  def vendorList = summary[Instance](Prism.instanceAgent, i => Some(Json.toJson(i.vendor)), "vendors")
  def appList = summaryFromTwo[Instance, Hardware](
    Prism.instanceAgent,
    i => i.app.flatMap{ app => i.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app))) },
    Prism.hardwareAgent,
    h => h.app.flatMap{ app => h.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app))) },
    "app",
    enableFilter = true
  )

  def dataList = itemList(Prism.dataAgent, "data")
  def data(id:String) = singleItem(Prism.dataAgent, id)
  def dataKeysList = summary[Data](Prism.dataAgent, d => Some(Json.toJson(d.key)), "keys")

  def dataLookup(key:String) = Action.async { implicit request =>
    ApiResult.filter{
      val app = request.getQueryString("app")
      val stage = request.getQueryString("stage")
      val stack = request.getQueryString("stack")
      val validKey = Prism.dataAgent.getTuples.filter(_._2.key == key).toSeq

      val errors:Map[String,String] = Map.empty ++
          (if (app.isEmpty) Some("app" -> "Must specify app") else None) ++
          (if (stage.isEmpty) Some("stage" -> "Must specify stage") else None) ++
          (if (validKey.size == 0) Some("key" -> s"The key name $key was not found") else None) ++
          (if (validKey.size > 1) Some("key" -> s"The key name $key was matched multiple times") else None)

      if (!errors.isEmpty) throw ApiCallException(Json.toJson(errors).as[JsObject])

      val (label, data) = validKey.head
      data.firstMatchingData(stack, app.get, stage.get).map(data => Map(label -> Seq(data))).getOrElse{
        throw ApiCallException(
          Json.obj("value" -> s"Key $key has no matching value for stack=${stack.getOrElse("")}, app=$app and stage=$stage")
        )
      }
    } reduce { result =>
      Json.toJson(result.head._2.head)
    }
  }

}