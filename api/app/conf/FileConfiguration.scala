package conf

import java.io.File
import com.typesafe.config.{Config, ConfigFactory}
import play.api.Configuration
import play.api.Mode

case class FileConfiguration(identity: Identity, classPathPrefix: String="env/") extends ConfigurationSource {
  def configuration(isTest: Boolean): Config = {
    val globalConfig = ConfigFactory.parseResourcesAnySyntax(s"${classPathPrefix}global.conf")

    if (isTest) globalConfig
    else {
      val stageConfig = ConfigFactory.parseResourcesAnySyntax(s"$classPathPrefix${identity.stage}.conf")
      val home = System.getProperty("user.home")
      val developerConfig = ConfigFactory.parseFileAnySyntax(new File(s"$home/.gu/${identity.stack}-${identity.app}.conf"))

      developerConfig.withFallback(stageConfig).withFallback(globalConfig)
    }
  }
}
