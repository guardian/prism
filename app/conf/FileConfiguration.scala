package conf

import java.io.File
import com.typesafe.config.ConfigFactory
import model.Identity
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
      // This file is created within the UserData
      val etcGuConfig = Configuration(
        ConfigFactory.parseFileAnySyntax(
          new File(s"/etc/gu/${identity.app}/${identity.stage}.conf")
        )
      )

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

      etcGuConfig
        .withFallback(developerConfig)
        .withFallback(stageConfig)
        .withFallback(globalConfig)
    }
  }
}
