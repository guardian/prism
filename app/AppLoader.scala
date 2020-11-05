import com.amazonaws.regions.Regions
import conf.{AWS, DynamoConfiguration, FileConfiguration, Identity, LogConfiguration}
import play.api.Configuration
import play.api._
import utils.{AWSCredentialProviders, Logging}

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
        AWSCredentialProviders.deployToolsCredentialsProviderChain,
        Regions.EU_WEST_1,
        identity
      ),
      FileConfiguration(identity)
    )

    val extraConfig: Configuration = extraConfigs.foldRight(Configuration.empty)(_.configuration(context.environment.mode).withFallback(_))

    val role: Option[String] = extraConfig.getOptional[String]("accounts.LoggingRole")
    val stream: Option[String] = extraConfig.getOptional[String]("accounts.LoggingStream")

    (role, stream) match {
      case (Some(role), Some(stream)) =>
        LogConfiguration.shipping(role, stream, identity)
      case _ => log.info("Missing role and/or stream configuration to enable log shipping to central ELK")
    }
    
    log.info(s"Loaded config $extraConfig")

    val combinedConfig: Configuration = extraConfig.withFallback(context.initialConfiguration)

    val contextWithConfig = context.copy(initialConfiguration = combinedConfig)

    new AppComponents(contextWithConfig).application
  }
}
