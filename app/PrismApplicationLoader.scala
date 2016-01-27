import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.{Regions, Region}
import conf.{FileConfiguration, DynamoConfiguration, Identity}
import play.api.ApplicationLoader.Context
import play.api.Configuration
import play.api.inject.guice.{GuiceApplicationBuilder, GuiceApplicationLoader}
import utils.Logging

class PrismApplicationLoader extends GuiceApplicationLoader() with Logging {

  override protected def builder(context: Context): GuiceApplicationBuilder = {
    val identity = Identity("prism", "prism", "DEV")

    val extraConfigs = List(
      DynamoConfiguration(
        new ProfileCredentialsProvider("deployTools"),
        Region.getRegion(Regions.EU_WEST_1),
        identity
      ),
      FileConfiguration(identity)
    )

    val extraConfig = extraConfigs.foldLeft(Configuration.empty)(_ ++ _.configuration(context.environment.mode))

    val combinedConfig = context.initialConfiguration ++ extraConfig

    log.info(s"Loaded config $extraConfig")

    new GuiceApplicationBuilder()
      .in(context.environment)
      .loadConfig(combinedConfig)
      .overrides(overrides(context): _*)
  }
}
