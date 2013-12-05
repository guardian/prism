package conf

import com.gu.conf.ConfigurationFactory
import utils.{UnnaturalOrdering, Logging}
import scala.language.postfixOps
import play.api.{Mode, Play}
import com.gu.management._
import com.gu.management.play.{RequestMetrics, Management => GuManagement}
import com.gu.management.logback.LogbackLevelPage
import collectors.{OpenstackOrigin, AmazonOrigin}

object App {
  val name: String = if (Play.current.mode == Mode.Test) "prism-test" else "prism"
}

class Configuration(val application: String, val webappConfDirectory: String = "env") extends Logging {
  protected val configuration = ConfigurationFactory.getConfiguration(application, webappConfDirectory)

  implicit class option2getOrException[T](option: Option[T]) {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  class NamedProperties(configuration: com.gu.conf.Configuration, prefix: String) {
    val NameRegex = s"""$prefix\\.([^\\.]*)\\..*""".r
    def names = configuration.getPropertyNames.flatMap {
      case NameRegex(name) => Some(name)
      case _ => None
    }
    def getStringPropertyOption(name:String, property:String): Option[String] =
      configuration.getStringProperty(s"$prefix.$name.$property")
    def getStringProperty(name:String, property:String, default:String): String = getStringPropertyOption(name, property).getOrElse(default)
    def getStringProperty(name:String, property:String): String = getStringPropertyOption(name, property).getOrElse {
      throw new IllegalStateException(s"No $property property specified for $name (expected $prefix.$name.$property)")
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

  object accounts {
    object aws extends NamedProperties(configuration, "accounts.aws") {
      val defaultRegion = configuration.getStringProperty("accounts.aws.defaultRegion", "eu-west-1")
      val list = names.toSeq.sorted.map { name =>
          val region = getStringProperty(name, "region", defaultRegion)
          val accessKey = getStringProperty(name, "accessKey")
          val secretKey = getStringProperty(name, "secretKey")
          AmazonOrigin(name, region, accessKey, secretKey)
        }
    }
    object openstack extends NamedProperties(configuration, "accounts.openstack") {
      val list = names.toSeq.sorted.map { name =>
        val tenant = getStringProperty(name, "tenant")
        val region = getStringProperty(name, "region")
        val endpoint = getStringProperty(name, "endpoint")
        val accessKey = getStringProperty(name, "user")
        val secretKey = getStringProperty(name, "secret")
        OpenstackOrigin(endpoint, region, tenant, accessKey, secretKey)
      }
    }
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

object Configuration extends Configuration(App.name, webappConfDirectory = "env")

object DeployInfoMode extends Enumeration {
  val URL = Value("URL")
  val Execute = Value("Execute")
}

object PlayRequestMetrics extends RequestMetrics.Standard

object Management extends GuManagement {
  val applicationName = App.name

  lazy val pages = List(
    new ManifestPage(),
    new Switchboard(applicationName, Seq()),
    new HealthcheckManagementPage,
    StatusPage(applicationName, PlayRequestMetrics.asMetrics),
    new PropertiesPage(Configuration.toString),
    new LogbackLevelPage(applicationName)
  )
}