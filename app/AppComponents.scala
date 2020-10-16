import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import conf.{AWS, DynamoConfiguration, FileConfiguration, Identity, PrismConfiguration}
import controllers.{Api, Application, OwnerApi, Prism}
import play.api.{ApplicationLoader, BuiltInComponentsFromContext, Configuration, Mode}
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import router.Routes
import utils.{Lifecycle, Logging, ScheduledAgent}

import scala.collection.mutable
import scala.concurrent.Future

class AppComponents(context: ApplicationLoader.Context)
  extends BuiltInComponentsFromContext(context)
    with play.filters.HttpFiltersComponents
    with play.filters.gzip.GzipFilterComponents
    with _root_.controllers.AssetsComponents
    with Logging {

  override def httpFilters: Seq[EssentialFilter] = {
    super.httpFilters :+ gzipFilter
  }

  val identity: Identity = {
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

  val extraConfig: Configuration = extraConfigs.foldRight(Configuration.empty)(_.configuration(context.environment.mode).withFallback(_))

  val combinedConfig: Configuration = extraConfig.withFallback(context.initialConfiguration)

  val prismConfig = new PrismConfiguration(combinedConfig)

  log.info(s"Loaded config $extraConfig")

  val prismController = new Prism(prismConfig)(actorSystem)

  /* Initialise agents */
  val lifecycleSingletons: mutable.Buffer[Lifecycle] = mutable.Buffer[Lifecycle]()
  // list of singletons - note these are inside onStart() to ensure logging has fully initialised
  lifecycleSingletons ++= List(
    ScheduledAgent
  )
  lifecycleSingletons ++= prismController.allAgents

  log.info("Calling init() on Lifecycle singletons: %s" format lifecycleSingletons.map(_.getClass.getName).mkString(", "))
  lifecycleSingletons foreach { singleton =>
    try {
      singleton.init(application)
    } catch {
      case t:Throwable => log.error("Caught unhandled exception whilst calling init() on Lifecycle singleton", t)
    }
  }

  context.lifecycle.addStopHook(() => Future {
    log.info("Calling shutdown() on Lifecycle singletons: %s" format lifecycleSingletons.reverse.map(_.getClass.getName).mkString(", "))
    lifecycleSingletons.reverse.foreach { singleton =>
      try {
        singleton.shutdown(application)
      } catch {
        case t:Throwable => log.error("Caught unhandled exception whilst calling shutdown() on Lifecycle singleton", t)
      }
    }
    lifecycleSingletons.clear()
  })

  lazy val homeController = new Application(controllerComponents, combinedConfig.underlying, () => router.documentation)

  lazy val apiController = new Api(controllerComponents, prismController, prismConfig)

  lazy val ownerController = new OwnerApi(controllerComponents)

  lazy val router: Router = new Routes(httpErrorHandler, homeController, apiController, assets, ownerController)
}