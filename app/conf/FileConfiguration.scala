package conf

import java.io.File
import com.typesafe.config.ConfigFactory
import play.api.Configuration
import play.api.Mode

case class FileConfiguration(
    identity: Identity,
    classPathPrefix: String = "env/"
) extends ConfigurationSource {
  def configuration(mode: Mode): Configuration = {
    val globalConfig = Configuration(
      ConfigFactory.parseResourcesAnySyntax(s"${classPathPrefix}global.conf")
    )

    if (mode == Mode.Test) globalConfig
    else {
      val stageConfig = Configuration(
        ConfigFactory.parseResourcesAnySyntax(
          s"$classPathPrefix${identity.stage}.conf"
        )
      )
      val home = System.getProperty("user.home")
      val developerConfig = Configuration(
        ConfigFactory.parseFileAnySyntax(
          new File(s"$home/.gu/${identity.stack}-${identity.app}.conf")
        )
      )

      developerConfig.withFallback(stageConfig).withFallback(globalConfig)
    }
  }
}
