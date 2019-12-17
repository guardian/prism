import akka.actor.ActorSystem
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import conf.{AWS, DynamoConfiguration, FileConfiguration, Identity}
import play.api.{ApplicationLoader, BuiltInComponentsFromContext}
import play.api.ApplicationLoader.Context
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSClient
import play.api.{Configuration, Mode}
import play.filters.gzip.GzipFilterComponents

import scala.concurrent.Future
import utils.{JsonpFilter, Logging}

class PrismApplicationLoader extends ApplicationLoader {

  def load(context: Context) = {
    val components = new PrismComponents(context) 
    AgentsLifecycle.onStart(components.application, components.prismAgents)
    context.lifecycle.addStopHook { () => 
      Future.successful(AgentsLifecycle.onStop(components.application))
    }
    components.application
  }

}


import router.Routes
  
class PrismComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with GzipFilterComponents
    with Logging {

  lazy val wsClient: WSClient = AhcWSClient()

  lazy val identity = {
      context.environment.mode match {
        case Mode.Prod => AWS.instance.identity
        case _ => None
      }
  }.getOrElse(Identity("deploy", "prism", "DEV"))
  log.info(s"Getting config for $identity")

  lazy val extraConfigs = List(
      DynamoConfiguration(
        new DefaultAWSCredentialsProviderChain(),
        Regions.EU_WEST_1,
        identity
      ),
      FileConfiguration(identity)
  )

  lazy val extraConfig = extraConfigs.foldLeft(Configuration.empty)(_ ++ _.configuration(context.environment.mode))
  log.info(s"Loaded config $extraConfig")

  override lazy val configuration: Configuration = context.initialConfiguration ++ extraConfig

  implicit val implicitActorSystem: ActorSystem = actorSystem

  implicit val ec = scala.concurrent.ExecutionContext.Implicits.global //TODO

  lazy val prismConfig = new conf.PrismConfiguration(this.configuration)

  lazy val prismAgents = new controllers.PrismAgents(actorSystem, prismConfig)

  lazy val appController = new controllers.Application(configuration)
  lazy val apiController = new controllers.Api(prismConfig, prismAgents)
  lazy val ownerApiController = new controllers.OwnerApi()
  lazy val assets = new controllers.Assets(httpErrorHandler)

  lazy val router: Routes = new Routes(httpErrorHandler, appController, apiController, assets, ownerApiController)

  val jsonpFilter = new JsonpFilter()
  val metricsFilters = prismAgents.globalCollectorAgent.metrics.PlayRequestMetrics.asFilters // FIXME: Is this necessary? Why not just use cloudwatch instead of gu.management?
  override lazy val httpFilters = Seq(gzipFilter, jsonpFilter) // FIXME: ++ metricsFilters
}