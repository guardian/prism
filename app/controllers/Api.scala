package controllers

import play.api.mvc._
import play.api.libs.json._
import scala.concurrent.duration.Duration
import play.api.libs.json.Json._
import collectors._
import scala.language.postfixOps
import utils.{ResourceFilter, Matchable, Logging}
import jsonimplicits.joda._
import agent._
import jsonimplicits.RequestWrites

object Api extends Controller with Api

trait Api extends Logging {
  this: Controller =>

  implicit def referenceWrites[T <: IndexedItem](implicit arnLookup:ArnLookup[T], tWrites:Writes[T], request: RequestHeader): Writes[Reference[T]] = new Writes[Reference[T]] {
    def writes(o: Reference[T]) = {
      request.getQueryString("_reference") match {
        case Some("inline") =>
          arnLookup.item(o.arn).flatMap { case (label, t) =>
            itemJson(item = t, label = Some(label), expand = true)
          }.getOrElse(JsString(o.arn))
        case Some("uri") =>
          Json.toJson(arnLookup.call(o.arn).absoluteURL()(request))
        case _ =>
          Json.toJson(o.arn)
      }
    }
  }
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
      val filtered = if (expand) json else JsObject(json.fields.filter(List("arn") contains _._1))
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

  def singleItem[T<:IndexedItem](agent:CollectorAgent[T], arn:String)
                                (implicit request: RequestHeader, writes: Writes[T]) =
    ApiResult.filter {
      val sources = agent.get()
      sources.flatMap{ datum =>
        datum.data.find(_.arn == arn).map(datum.label -> Seq(_))
      }.toMap
    } reduce { sources =>
      sources.headOption.map {
        case (label, items) =>
          itemJson(items.head, expand = true, label=Some(label)).get
      } getOrElse {
        throw ApiCallException(Json.obj("arn" -> s"Item with arn $arn doesn't exist"), NOT_FOUND)
      }
    }

  def itemList[T<:IndexedItem](agent:CollectorAgent[T], objectKey:String, defaultFilter: (String,String)*)
                              (implicit request: RequestHeader, writes: Writes[T]) =
    ApiResult.filter {
      val expand = request.getQueryString("_brief").isEmpty
      val filter =  ResourceFilter.fromRequestWithDefaults(defaultFilter:_*)
      agent.get().map { agent =>
        agent.label ->
          agent.data.flatMap(host => itemJson(host, expand, Some(agent.label), filter=filter))
      }.toMap
    } reduce { collection =>
      Json.obj(
        objectKey -> toJson(collection.values.flatten)
      )
    }

  def instanceList = Action.async { implicit request =>
    itemList(Prism.instanceAgent, "instances", "vendorState" -> "running", "vendorState" -> "ACTIVE")
  }
  def instance(arn:String) = Action.async { implicit request =>
    singleItem(Prism.instanceAgent, arn)
  }

  def securityGroupList = Action.async { implicit request =>
    itemList(Prism.securityGroupAgent, "security-groups")
  }

  def securityGroup(arn:String) = Action.async { implicit request =>
    singleItem(Prism.securityGroupAgent, arn)
  }

  def imageList = Action.async { implicit request =>
    itemList(Prism.imageAgent, "images")
  }
  def image(arn:String) = Action.async { implicit request =>
    singleItem(Prism.imageAgent, arn)
  }

  def launchConfigurationList = Action.async { implicit request =>
    itemList(Prism.launchConfigurationAgent, "launch-configurations")
  }
  def launchConfiguration(arn:String) = Action.async { implicit request =>
    singleItem(Prism.launchConfigurationAgent, arn)
  }

  def roleList = summary[Instance](Prism.instanceAgent, i => i.role.map(Json.toJson(_)), "roles")
  def mainclassList = summary[Instance](Prism.instanceAgent, i => i.mainclasses.map(Json.toJson(_)), "mainclasses")
  def stackList = summary[Instance](Prism.instanceAgent, i => i.stack.map(Json.toJson(_)), "stacks")
  def stageList = summary[Instance](Prism.instanceAgent, i => i.stage.map(Json.toJson(_)), "stages")(conf.PrismConfiguration.stages.ordering)
  def regionList = summary[Instance](Prism.instanceAgent, i => Some(Json.toJson(i.region)), "regions")
  def vendorList = summary[Instance](Prism.instanceAgent, i => Some(Json.toJson(i.vendor)), "vendors")
  def appList = summary[Instance](
    Prism.instanceAgent,
    i => i.app.flatMap{ app => i.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app))) },
    "app",
    enableFilter = true
  )

  def dataList = Action.async { implicit request =>
    itemList(Prism.dataAgent, "data")
  }
  def data(arn:String) = Action.async { implicit request =>
    singleItem(Prism.dataAgent, arn)
  }
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