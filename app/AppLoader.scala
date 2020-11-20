import com.amazonaws.regions.Regions
import com.amazonaws.util.EC2MetadataUtils
import conf._
import play.api.{Configuration, _}
import utils.{AWSCredentialProviders, Logging}

import scala.util.Try

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

    val stream: Option[String] = extraConfig.getOptional[String]("LoggingStream")
    stream match {
      case Some(stream) =>
        val maybeInstanceId = Try(EC2MetadataUtils.getInstanceId).toOption
        val loggingContext = Map("buildId" -> prism.BuildInfo.buildNumber) ++ maybeInstanceId.map("instanceId" -> _)
        LogConfiguration.shipping(stream, identity, loggingContext)
      case _ => log.info("Missing stream configuration to enable log shipping to central ELK")
    }

    log.info(s"Loaded config $extraConfig")

    val combinedConfig: Configuration = extraConfig.withFallback(context.initialConfiguration)

    val contextWithConfig = context.copy(initialConfiguration = combinedConfig)

    try {
      new AppComponents(contextWithConfig).application
    } catch {
      case t: Throwable =>
        log.error("Unable to initialise prism", t)
        throw t
    }
  }
}
