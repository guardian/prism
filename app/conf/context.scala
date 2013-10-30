package conf

import com.gu.conf.ConfigurationFactory
import utils.{UnnaturalOrdering, Logging}
import scala.language.postfixOps
import play.api.{Mode, Play}


class Configuration(val application: String, val webappConfDirectory: String = "env") extends Logging {
  protected val configuration = ConfigurationFactory.getConfiguration(application, webappConfDirectory)

  implicit class option2getOrException[T](option: Option[T]) {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  object deployinfo {
    lazy val location: String = configuration.getStringProperty("deployinfo.location").getOrException("Deploy Info location not specified")
    lazy val mode: DeployInfoMode.Value = configuration.getStringProperty("deployinfo.mode").flatMap{ name =>
      DeployInfoMode.values.find(_.toString.equalsIgnoreCase(name))
    }.getOrElse(DeployInfoMode.URL)
    lazy val staleMinutes: Int = configuration.getIntegerProperty("deployinfo.staleMinutes", 15)
    lazy val refreshSeconds: Int = configuration.getIntegerProperty("deployinfo.refreshSeconds", 60)
    lazy val timeoutSeconds: Int = configuration.getIntegerProperty("deployinfo.timeoutSeconds", 180)
    lazy val lazyStartup: Boolean = configuration.getStringProperty("deployinfo.lazyStartup", "false") == "true"
  }

  object logging {
    lazy val verbose = configuration.getStringProperty("logging").exists(_.equalsIgnoreCase("VERBOSE"))
  }

  object stages {
    lazy val order = configuration.getStringPropertiesSplitByComma("stages.order").filterNot(""==)
    lazy val ordering = UnnaturalOrdering(order, aliensAtEnd = true)
  }

  object urls {
    lazy val publicPrefix: String = configuration.getStringProperty("urls.publicPrefix", "http://localhost:9000")
  }

  override def toString: String = configuration.toString
}

object Configuration extends Configuration(if (Play.current.mode == Mode.Test) "prism-test" else "prism", webappConfDirectory = "env")

object DeployInfoMode extends Enumeration {
  val URL = Value("URL")
  val Execute = Value("Execute")
}
