import agent.ObjectStoreAgent
import collectors.Lambda
import conf.PrismConfiguration
import controllers._
import play.api.mvc.EssentialFilter
import play.api.routing.Router
import play.api.{ApplicationLoader, BuiltInComponentsFromContext}
import play.filters.HttpFiltersComponents
import play.filters.gzip.GzipFilterComponents
import router.Routes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import utils.{AWSCredentialProviders, Lifecycle, Logging, ScheduledAgent}

import scala.collection.mutable
import scala.concurrent.Future

class AppComponents(context: ApplicationLoader.Context)
  extends BuiltInComponentsFromContext(context)
    with HttpFiltersComponents
    with GzipFilterComponents
    with AssetsComponents
    with Logging {

  override def httpFilters: Seq[EssentialFilter] = {
    super.httpFilters :+ gzipFilter
  }

  val prismConfig = new PrismConfiguration(configuration.underlying)

  val s3Client = S3Client.builder
    .credentialsProvider(AWSCredentialProviders.deployToolsCredentialsProviderChain)
    .region(Region.EU_WEST_1)
    .build

  val prismController = new Prism(prismConfig, s3Client)(actorSystem)

  /* Initialise agents */
  val lifecycleSingletons: mutable.Buffer[Lifecycle] = mutable.Buffer[Lifecycle]()
  // list of singletons - note these are inside onStart() to ensure logging has fully initialised
  lifecycleSingletons ++= List(
    ScheduledAgent
  )
  lifecycleSingletons ++= prismController.allInternalAgents

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


  lazy val homeController = new Application(controllerComponents, () => router.documentation)

  lazy val apiController = new Api(controllerComponents, prismController, prismConfig)

  lazy val ownerController = new OwnerApi(controllerComponents)

  lazy val router: Router = new Routes(httpErrorHandler, homeController, apiController, assets, ownerController)
}