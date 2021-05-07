package controllers

import agent._
import collectors._
import conf.PrismConfiguration
import play.api.http.Status
import play.api.libs.json.Json._
import play.api.libs.json._
import play.api.mvc.{Action, RequestHeader, Result, _}
import utils.{Matchable, ResourceFilter}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

//noinspection TypeAnnotation
class Api (cc: ControllerComponents, prismDataStore: Prism, prismConfiguration: PrismConfiguration)(implicit executionContext: ExecutionContext) extends AbstractController(cc) {
    implicit def referenceWrites[T <: IndexedItem](implicit arnLookup:ArnLookup[T], tWrites:Writes[T], request: RequestHeader): Writes[Reference[T]] = (o: Reference[T]) => {
      request.getQueryString("_reference") match {
        case Some("inline") =>
          arnLookup.item(o.arn, prismDataStore).flatMap { case (label, t) =>
            Api.itemJson(item = t, label = Some(label), expand = true)
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
        val sources = prismDataStore.sourceStatusAgent.sources
        Map(sources.label -> sources.data.map(toJson(_)).filter(filter.isMatch))
      } reduce { collection =>
        toJson(collection.flatMap(_._2))
      }
    }

    def sourceAccounts: Action[AnyContent] = Action.async { implicit request =>
      ApiResult.noSource {
        val accounts = prismDataStore.sourceStatusAgent.sources.data.map { source =>
          val accountName = source.latest.origin.account
          val accountNumber = source.latest.origin match {
            case a: AmazonOrigin => a.accountNumber
            case _ => None
          }
          AWSAccount(accountNumber, accountName)
        }.toList.distinct
        Json.toJson(accounts)
      }
    }

    def healthCheck = Action.async { implicit request =>
      ApiResult.filter {
        val sources = prismDataStore.sourceStatusAgent.sources
        val notInitialisedSources = sources.data.filter(_.state.status != "success")
        if (notInitialisedSources.isEmpty) Map.empty else Map(sources.label -> notInitialisedSources)
      } reduce { notInitialisedSources =>
        if (notInitialisedSources.isEmpty) {
          Json.obj("healthcheck" -> "initialised")
        } else
          throw ApiCallException(Json.obj("healthcheck" -> "not yet initialised", "sources" -> notInitialisedSources.values.headOption), SERVICE_UNAVAILABLE)
      }
    }

    def find = Action.async { implicit request =>
      val filter = ResourceFilter.fromRequest
      ApiResult.filter {
        val sources = prismDataStore.allAgents.map(_.get())
        sources.flatMap{ agent =>
          agent.map{ datum =>
            datum.label -> datum.data.filter(d => filter.isMatch(d.fieldIndex))
          }
        }.toMap
      } reduce { sources =>
        val results = sources.flatMap { case (label, dataItems) =>
          dataItems.map { data =>
            Json.obj(
              "type" -> label.resourceType.name,
              "href" -> data.call.absoluteURL()
            )
          }
        }
        Json.toJson(results)
      }
    }

    def instanceList = Action.async { implicit request =>
      Api.itemList(prismDataStore.instanceAgent, "instances", "vendorState" -> "running", "vendorState" -> "ACTIVE")
    }
    def instance(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.instanceAgent, arn)
    }

    def lambdaList = Action.async { implicit request =>
      Api.itemList(prismDataStore.lambdaAgent, "lambdas")
    }
    def lambda(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.lambdaAgent, arn)
    }

    def securityGroupList = Action.async { implicit request =>
      Api.itemList(prismDataStore.securityGroupAgent, "security-groups")
    }

    def securityGroup(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.securityGroupAgent, arn)
    }

    def imageList = Action.async { implicit request =>
      Api.itemList(prismDataStore.imageAgent, "images")
    }
    def image(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.imageAgent, arn)
    }

    def launchConfigurationList = Action.async { implicit request =>
      Api.itemList(prismDataStore.launchConfigurationAgent, "launch-configurations")
    }
    def launchConfiguration(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.launchConfigurationAgent, arn)
    }

    def serverCertificateList = Action.async { implicit request =>
      Api.itemList(prismDataStore.serverCertificateAgent, "server-certificates")
    }
    def serverCertificate(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.serverCertificateAgent, arn)
    }

    def acmCertificateList = Action.async { implicit request =>
      Api.itemList(prismDataStore.acmCertificateAgent, "acm-certificates")
    }
    def acmCertificate(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.acmCertificateAgent, arn)
    }

    def route53ZoneList = Action.async { implicit request =>
      Api.itemList(prismDataStore.route53ZoneAgent, "route53-zones")
    }
    def route53Zone(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.route53ZoneAgent, arn)
    }

    def elbList = Action.async { implicit request =>
      Api.itemList(prismDataStore.elbAgent, "elbs")
    }
    def elb(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.elbAgent, arn)
    }

    def bucketList = Action.async { implicit request =>
      Api.itemList(prismDataStore.bucketAgent, "buckets")
    }
    def bucket(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.bucketAgent, arn)
    }

    def reservationList = Action.async { implicit request =>
      Api.itemList(prismDataStore.reservationAgent, "reservations")
    }
    def reservation(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.reservationAgent, arn)
    }

    def rdsList = Action.async { implicit request =>
      Api.itemList(prismDataStore.rdsAgent, "rds-instances")
    }
    def rds(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.rdsAgent, arn)
    }

    def vpcList = Action.async { implicit request =>
      Api.itemList(prismDataStore.vpcAgent, "vpcs")
    }

    def vpcs(arn: String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.vpcAgent, arn)
    }

    def cloudformationStackList = Action.async { implicit request =>
      Api.itemList(prismDataStore.cloudformationStackAgent, "cloudformation-stacks")
    }

    def cloudformationStack(arn: String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.cloudformationStackAgent, arn)
    }

    private def stackExtractor(i: IndexedItemWithStack) = i.stack.map(Json.toJson(_))
    private def stageExtractor(i: IndexedItemWithStage) = i.stage.map(Json.toJson(_))
    def roleList = summary[Instance](prismDataStore.instanceAgent, i => i.role.map(Json.toJson(_)), "roles")
    def mainclassList = summary[Instance](prismDataStore.instanceAgent, i => i.mainclasses.map(Json.toJson(_)), "mainclasses")
    def stackList = summaryFromTwo[Instance, Lambda](prismDataStore.instanceAgent, stackExtractor, prismDataStore.lambdaAgent, stackExtractor, "stacks")(prismConfiguration.stages.ordering)
    def stageList = summaryFromTwo[Instance, Lambda](prismDataStore.instanceAgent, stageExtractor, prismDataStore.lambdaAgent, stageExtractor, "stages")(prismConfiguration.stages.ordering)
    def regionList = summary[Instance](prismDataStore.instanceAgent, i => Some(Json.toJson(i.region)), "regions")
    def vendorList = summary[Instance](prismDataStore.instanceAgent, i => Some(Json.toJson(i.vendor)), "vendors")

    private def appListExtractor(i: IndexedItemWithCoreTags) = i.app.flatMap { app =>
      i.stack.map(stack => Json.toJson(Map("stack" -> stack, "app" -> app)))
    }
    def appList = summaryFromTwo[Instance, Lambda](
      prismDataStore.instanceAgent,
      appListExtractor,
      prismDataStore.lambdaAgent,
      appListExtractor,
      "app",
      enableFilter = true
    )

    private def appsWithCdkVersionExtractor(i: IndexedItemWithCoreTags) = i.app.map { app =>
      Json.toJson(
        Map(
          "app" -> app,
          "stack" -> i.stack.getOrElse("unknown"),
          "stage" -> i.stage.getOrElse("unknown"),
          "guCdkVersion" -> i.guCdkVersion.getOrElse("n/a")
        )
      )
    }
    def appsWithCdkVersion = summaryFromTwo[Instance, Lambda](
      prismDataStore.instanceAgent,
      appsWithCdkVersionExtractor,
      prismDataStore.lambdaAgent,
      appsWithCdkVersionExtractor,
      "apps-with-cdk-version",
      enableFilter = true
    )

    def dataList = Action.async { implicit request =>
      Api.itemList(prismDataStore.dataAgent, "data")
    }
    def data(arn:String) = Action.async { implicit request =>
      Api.singleItem(prismDataStore.dataAgent, arn)
    }
    def dataKeysList = summary[Data](prismDataStore.dataAgent, d => Some(Json.toJson(d.key)), "keys")

    def dataLookup(key:String) = Action.async { implicit request =>
      ApiResult.filter{
        val app = request.getQueryString("app")
        val stage = request.getQueryString("stage")
        val stack = request.getQueryString("stack")
        val validKey = prismDataStore.dataAgent.getTuples.filter(_._2.key == key).toSeq

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
      } reduce { result =>
        Json.toJson(result.head._2.head)
      }
    }
}

object Api extends Status {
  import jsonimplicits.model._

  def singleItem[T<:IndexedItem](agent:CollectorAgent[T], arn:String)
                                (implicit request: RequestHeader, writes: Writes[T], executionContext: ExecutionContext): Future[Result] =
    ApiResult.filter {
      val sources = agent.get()
      sources.flatMap{ datum =>
        datum.data.find(_.arn == arn).map(datum.label -> Seq(_))
      }.toMap
    } reduce { sources =>
      sources.headOption.map {
        case (label, items) =>
          Api.itemJson(items.head, expand = true, label=Some(label)).get
      } getOrElse {
        throw ApiCallException(Json.obj("arn" -> s"Item with arn $arn doesn't exist"), NOT_FOUND)
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

  def itemList[T<:IndexedItem](agent:CollectorAgentTrait[T], objectKey:String, defaultFilter: (String,String)*)
                              (implicit request: RequestHeader, writes: Writes[T], executionContext: ExecutionContext): Future[Result] =
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
}