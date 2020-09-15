import play.api._
import play.api.{Mode, Configuration}
import play.api.routing.Router
import conf.{AWS, FileConfiguration, DynamoConfiguration, Identity}
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions

class PrismApplicationLoader extends ApplicationLoader {
  private var components: PrismComponents = _

  def load(context: ApplicationLoader.Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    components = new PrismComponents(context)
    components.application
  }
}

class PrismComponents(context: ApplicationLoader.Context)
  extends BuiltInComponentsFromContext(context)
    with play.filters.HttpFiltersComponents
    with _root_.controllers.AssetsComponents
    with Logging {

  val identity: Identity = {
    context.environment.mode match {
      case Mode.Prod => AWS.instance.identity
      case _ => None
    }
  }.getOrElse(Identity("deploy", "prism", "DEV"))

  logger.info(s"Getting config for $identity")

  val extraConfigs = List(
    DynamoConfiguration(
      new DefaultAWSCredentialsProviderChain(),
      Regions.EU_WEST_1,
      identity
    ),
    FileConfiguration(identity)
  )

  // TODO: Is this okay?
  // val extraConfig: Configuration = extraConfigs.foldLeft(Configuration.empty)(_ ++ _.configuration(context.environment.mode))
  val extraConfig: Configuration = extraConfigs.foldRight(Configuration.empty)(_.configuration(context.environment.mode).withFallback(_))

  val combinedConfig: Configuration = extraConfig.withFallback(context.initialConfiguration)

  logger.info(s"Loaded config $extraConfig")

  lazy val homeController = new _root_.controllers.Application(controllerComponents, combinedConfig.underlying)

  lazy val ownerController = new _root_.controllers.OwnerApi(controllerComponents, executionContext)

  lazy val router: Router = new _root_.router.Routes(httpErrorHandler, homeController, assets, ownerController)

  homeController.documentation = router.documentation
}