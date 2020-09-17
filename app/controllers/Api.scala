package controllers

import agent._
import collectors._
import conf.PrismConfiguration
import javax.inject.Inject
import play.api.Logging
import play.api.mvc._
import play.api.mvc.{Action, RequestHeader, Result}
import play.api.libs.json._
import play.api.libs.json.Json._

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.postfixOps
import utils.{Matchable, ResourceFilter}

class Api @Inject()(cc: ControllerComponents, prismController: Prism, executionContext: ExecutionContext, prismConfiguration: PrismConfiguration) extends AbstractController(cc) with Logging {
    implicit def referenceWrites[T <: IndexedItem](implicit arnLookup:ArnLookup[T], tWrites:Writes[T], request: RequestHeader): Writes[Reference[T]] = (o: Reference[T]) => {
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
    import jsonimplicits.model._

    def sortString(jsv: JsValue):String =
      jsv match {
        case JsString(str) => str
        case JsArray(seq) => seq.map(sortString).mkString
        case JsObject(fields) => fields.map{case(key, value) => s"$key${sortString(value)}"}.mkString
        case _ => ""
      }

    def summary[T<:IndexedItem](sourceAgent: CollectorAgent[T], transform: T => Iterable[JsValue], key: String, enableFilter: Boolean = false)(implicit ordering:Ordering[String]): Action[AnyContent] =
      Action.async { implicit request =>
        ApiResult.filter[JsValue] {
          sourceAgent.get().map { datum => datum.label -> datum.data.flatMap(transform)}.toMap
        } reduce ({ transformed =>
          val objects = transformed.values.toSeq.flatten.distinct.sortBy(sortString)(ordering)
          val filteredObjects = if (enableFilter) {
            val filter = ResourceFilter.fromRequest
            objects.filter(filter.isMatch)
          } else objects
          Json.obj(key -> Json.toJson(filteredObjects))
        }, executionContext)
      }

    def summaryFromTwo[T<:IndexedItem, U<:IndexedItem](sourceTAgent: CollectorAgent[T],
                                                       transformT: T => Iterable[JsValue],
                                                       sourceUAgent: CollectorAgent[U],
                                                       transformU: U => Iterable[JsValue],
                                                       key: String,
                                                       enableFilter: Boolean = false
                                                        )(implicit ordering:Ordering[String]): Action[AnyContent] =
      Action.async { implicit request =>
        ApiResult.filter[JsValue] {
          sourceTAgent.get().map { datum => datum.label -> datum.data.flatMap(transformT)}.toMap ++
          sourceUAgent.get().map { datum => datum.label -> datum.data.flatMap(transformU)}.toMap
        } reduce({ transformed =>
          val objects = transformed.values.toSeq.flatten.distinct.sortBy(sortString)(ordering)
          val filteredObjects = if (enableFilter) {
            val filter = ResourceFilter.fromRequest
            objects.filter(filter.isMatch)
          } else objects
          Json.obj(key -> Json.toJson(filteredObjects))
        }, executionContext)
      }

    def sources: Action[AnyContent] = Action.async { implicit request =>
      ApiResult.filter {
        val filter = ResourceFilter.fromRequest
        val sources = prismController.labelAgent.sources
        Map(sources.label -> sources.data.map(toJson(_)).filter(filter.isMatch))
      } reduce({ collection =>
        toJson(collection.flatMap(_._2))
      }, executionContext)
    }

    def healthCheck: Action[AnyContent] = Action.async { implicit request =>
      ApiResult.filter {
        val sources = prismController.labelAgent.sources
        val notInitialisedSources = sources.data.filter(_.state.status != "success")
        if (notInitialisedSources.isEmpty) Map.empty else Map(sources.label -> notInitialisedSources)
      } reduce({ notInitialisedSources =>
        if (notInitialisedSources.isEmpty)
          Json.obj("healthcheck" -> "initialised")
        else
          throw ApiCallException(Json.obj("healthcheck" -> "not yet initialised", "sources" -> notInitialisedSources.values.headOption), SERVICE_UNAVAILABLE)
      }, executionContext)
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

    def find: Action[AnyContent] = Action.async { implicit request =>
      val filter = ResourceFilter.fromRequest
      ApiResult.filter {
        val sources = prismController.allAgents.map(_.get())
        sources.flatMap{ agent =>
          agent.map{ datum =>
            datum.label -> datum.data.filter(d => filter.isMatch(d.fieldIndex))
          }
        }.toMap
      } reduce({ sources =>
        val results = sources.flatMap { case (label, dataItems) =>
          dataItems.map { data =>
            Json.obj(
              "type" -> label.resource.name,
              "href" -> data.call.absoluteURL()
            )
          }
        }
        Json.toJson(results)
      }, executionContext)
    }

    def singleItem[T<:IndexedItem](agent:CollectorAgent[T], arn:String)
                                  (implicit request: RequestHeader, writes: Writes[T]): Future[Result] =
      ApiResult.filter {
        val sources = agent.get()
        sources.flatMap{ datum =>
          datum.data.find(_.arn == arn).map(datum.label -> Seq(_))
        }.toMap
      } reduce({ sources =>
        sources.headOption.map {
          case (label, items) =>
            itemJson(items.head, expand = true, label=Some(label)).get
        } getOrElse {
          throw ApiCallException(Json.obj("arn" -> s"Item with arn $arn doesn't exist"), NOT_FOUND)
        }
      }, executionContext)

    def itemList[T<:IndexedItem](agent:CollectorAgent[T], objectKey:String, defaultFilter: (String,String)*)
                                (implicit request: RequestHeader, writes: Writes[T]): Future[Result] =
      ApiResult.filter {
        val expand = request.getQueryString("_brief").isEmpty
        val filter =  ResourceFilter.fromRequestWithDefaults(defaultFilter:_*)
        agent.get().map { agent =>
          agent.label ->
            agent.data.flatMap(host => itemJson(host, expand, Some(agent.label), filter=filter))
        }.toMap
      } reduce({ collection =>
        Json.obj(
          objectKey -> toJson(collection.values.flatten)
        )
      }, executionContext)

    def instanceList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.instanceAgent, "instances", "vendorState" -> "running", "vendorState" -> "ACTIVE")
    }
    def instance(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.instanceAgent, arn)
    }

    def lambdaList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.lambdaAgent, "lambdas")
    }
    def lambda(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.lambdaAgent, arn)
    }

    def securityGroupList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.securityGroupAgent, "security-groups")
    }

    def securityGroup(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.securityGroupAgent, arn)
    }

    def imageList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.imageAgent, "images")
    }
    def image(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.imageAgent, arn)
    }

    def launchConfigurationList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.launchConfigurationAgent, "launch-configurations")
    }
    def launchConfiguration(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.launchConfigurationAgent, arn)
    }

    def serverCertificateList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.serverCertificateAgent, "server-certificates")
    }
    def serverCertificate(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.serverCertificateAgent, arn)
    }

    def acmCertificateList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.acmCertificateAgent, "acm-certificates")
    }
    def acmCertificate(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.acmCertificateAgent, arn)
    }

    def route53ZoneList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.route53ZoneAgent, "route53-zones")
    }
    def route53Zone(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.route53ZoneAgent, arn)
    }

    def elbList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.elbAgent, "elbs")
    }
    def elb(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.elbAgent, arn)
    }

    def bucketList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.bucketAgent, "buckets")
    }
    def bucket(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.bucketAgent, arn)
    }

    def reservationList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.reservationAgent, "reservations")
    }
    def reservation(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.reservationAgent, arn)
    }

    private def stackExtractor(i: IndexedItemWithStack) = i.stack.map(Json.toJson(_))
    private def stageExtractor(i: IndexedItemWithStage) = i.stage.map(Json.toJson(_))
    def roleList: Action[AnyContent] = summary[Instance](prismController.instanceAgent, i => i.role.map(Json.toJson(_)), "roles")
    def mainclassList: Action[AnyContent] = summary[Instance](prismController.instanceAgent, i => i.mainclasses.map(Json.toJson(_)), "mainclasses")
    def stackList: Action[AnyContent] = summaryFromTwo[Instance, Lambda](prismController.instanceAgent, stackExtractor, prismController.lambdaAgent, stackExtractor, "stacks")(prismConfiguration.stages.ordering)
    def stageList: Action[AnyContent] = summaryFromTwo[Instance, Lambda](prismController.instanceAgent, stageExtractor, prismController.lambdaAgent, stageExtractor, "stages")(prismConfiguration.stages.ordering)
    def regionList: Action[AnyContent] = summary[Instance](prismController.instanceAgent, i => Some(Json.toJson(i.region)), "regions")
    def vendorList: Action[AnyContent] = summary[Instance](prismController.instanceAgent, i => Some(Json.toJson(i.vendor)), "vendors")
    def appList: Action[AnyContent] = summary[Instance](
      prismController.instanceAgent,
      i => i.app.flatMap{ app => i.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app))) },
      "app",
      enableFilter = true
    )

    def dataList: Action[AnyContent] = Action.async { implicit request =>
      itemList(prismController.dataAgent, "data")
    }
    def data(arn:String): Action[AnyContent] = Action.async { implicit request =>
      singleItem(prismController.dataAgent, arn)
    }
    def dataKeysList: Action[AnyContent] = summary[Data](prismController.dataAgent, d => Some(Json.toJson(d.key)), "keys")

    def dataLookup(key:String): Action[AnyContent] = Action.async { implicit request =>
      ApiResult.filter{
        val app = request.getQueryString("app")
        val stage = request.getQueryString("stage")
        val stack = request.getQueryString("stack")
        val validKey = prismController.dataAgent.getTuples.filter(_._2.key == key).toSeq

        val errors:Map[String,String] = Map.empty ++
            (if (app.isEmpty) Some("app" -> "Must specify app") else None) ++
            (if (stage.isEmpty) Some("stage" -> "Must specify stage") else None) ++
            (if (stage.isEmpty) Some("stack" -> "Must specify stack") else None) ++
            (if (validKey.isEmpty) Some("key" -> s"The key name $key was not found") else None) ++
            (if (validKey.size > 1) Some("key" -> s"The key name $key was matched multiple times") else None)

        if (errors.nonEmpty) throw ApiCallException(Json.toJson(errors).as[JsObject])

        val (label, data) = validKey.head
        data.firstMatchingData(stack.get, app.get, stage.get).map(data => Map(label -> Seq(data))).getOrElse{
          throw ApiCallException(
            Json.obj("value" -> s"Key $key has no matching value for stack=$stack, app=$app and stage=$stage")
          )
        }
      } reduce({ result =>
        Json.toJson(result.head._2.head)
      }, executionContext)
    }

}