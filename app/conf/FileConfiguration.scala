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
      val stageConfigFile = if (identity.stage == "DEV") {
        val home = System.getProperty("user.home")
        new File(s"$home/.gu/${identity.app}/${identity.stage}.conf")
      } else {
        // This file is created within the EC2 instance UserData
        new File(s"/etc/gu/${identity.app}/${identity.stage}.conf")
      }

      if (!stageConfigFile.exists || !stageConfigFile.isFile) {
        throw new RuntimeException(
          s"Config file does not exist (path: ${stageConfigFile.getAbsolutePath})"
        )
      }

      val stageConfig = Configuration(
        ConfigFactory.parseFileAnySyntax(stageConfigFile)
      )

      stageConfig.withFallback(globalConfig)
    }
  }
}
