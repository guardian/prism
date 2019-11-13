import akka.actor.ActorSystem
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.{Regions, Region}
import conf.{AWS, FileConfiguration, DynamoConfiguration, Identity}
import play.api.{ApplicationLoader, BuiltInComponentsFromContext}
import play.api.ApplicationLoader.Context
import play.api.{Mode, Configuration}
import play.api.mvc.EssentialFilter
import scala.concurrent.Future
import utils.Logging


class PrismApplicationLoader extends ApplicationLoader {

  def load(context: Context) = {
    val components = new PrismComponents(context) 
    Global.onStart(components.application, components.prismAgents)
    context.lifecycle.addStopHook { () => 
      Future.successful(Global.onStop(components.application)) 
    }
    components.application
  }

}


import router.Routes
  
class PrismComponents(context: Context) extends BuiltInComponentsFromContext(context) with Logging {
  
  val identity = {
      context.environment.mode match {
        case Mode.Prod => AWS.instance.identity
        case _ => None
      }
  }.getOrElse(Identity("deploy", "prism", "DEV"))
  log.info(s"Getting config for $identity")

  val extraConfigs = List(
      DynamoConfiguration(
        new DefaultAWSCredentialsProviderChain(),
        Regions.EU_WEST_1,
        identity
      ),
      FileConfiguration(identity)
  )

  val extraConfig = extraConfigs.foldLeft(Configuration.empty)(_ ++ _.configuration(context.environment.mode))
  log.info(s"Loaded config $extraConfig")

  override lazy val configuration: Configuration = context.initialConfiguration ++ extraConfig


  implicit val implicitActorSystem: ActorSystem = actorSystem

  implicit val ec = scala.concurrent.ExecutionContext.Implicits.global //TODO

  lazy val prismConfig = new conf.PrismConfiguration(this.configuration)

  lazy val prismAgents = new controllers.PrismAgents(actorSystem, prismConfig)

  lazy val appController = new controllers.Application()
  lazy val apiController = new controllers.Api(prismConfig, prismAgents)
  lazy val ownerApiController = new controllers.OwnerApi()
  lazy val assets = new controllers.Assets(httpErrorHandler)

  lazy val router = new Routes(httpErrorHandler, appController, apiController, assets, ownerApiController)

}