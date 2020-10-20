import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import conf.{AWS, DynamoConfiguration, FileConfiguration, Identity}
import play.api.{Application, ApplicationLoader, Configuration, LoggerConfigurator, Mode}
import utils.Logging

class AppLoader extends ApplicationLoader with Logging {
  def load(context: ApplicationLoader.Context): Application = {
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
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

    log.info(s"Loaded config $extraConfig")

    val combinedConfig: Configuration = extraConfig.withFallback(context.initialConfiguration)

    val contextWithConfig = context.copy(initialConfiguration = combinedConfig)

    new AppComponents(contextWithConfig).application
  }
}