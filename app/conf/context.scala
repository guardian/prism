package conf

import com.gu.conf.ConfigurationFactory
import utils.{UnnaturalOrdering, Logging}
import scala.language.postfixOps
import play.api.{Mode, Play}
import com.gu.management._
import com.gu.management.play.{RequestMetrics, Management => GuManagement}
import com.gu.management.logback.LogbackLevelPage
import collectors.{JsonOrigin, OpenstackOrigin, AmazonOrigin}

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
    def getStringPropertiesSplitByComma(name:String, property:String) =
      configuration.getStringPropertiesSplitByComma(s"$prefix.$name.$property")
  }

  object accounts {
    lazy val lazyStartup = configuration.getStringProperty("accounts.lazyStartup", "true") == "true"
    lazy val all = aws.list ++ openstack.list ++ json.list
    def forResource(resource:String) = all.filter(origin => origin.resources.isEmpty || origin.resources.contains(resource))
    object aws extends NamedProperties(configuration, "accounts.aws") {
      lazy val defaultRegion = configuration.getStringProperty("accounts.aws.defaultRegion", "eu-west-1")
      val list = names.toSeq.sorted.map { name =>
          val region = getStringProperty(name, "region", defaultRegion)
          val accessKey = getStringProperty(name, "accessKey")
          val secretKey = getStringProperty(name, "secretKey")
          val resources = getStringPropertiesSplitByComma(name, "resources")
          AmazonOrigin(name, region, accessKey, resources.toSet)(secretKey)
        }
    }
    object openstack extends NamedProperties(configuration, "accounts.openstack") {
      lazy val list = names.toSeq.sorted.map { name =>
        val tenant = getStringProperty(name, "tenant")
        val region = getStringProperty(name, "region")
        val endpoint = getStringProperty(name, "endpoint")
        val accessKey = getStringProperty(name, "user")
        val secretKey = getStringProperty(name, "secret")
        val resources = getStringPropertiesSplitByComma(name, "resources")
        val stagePrefix = getStringPropertyOption(name, "stagePrefix")
        OpenstackOrigin(endpoint, region, tenant, accessKey, resources.toSet, stagePrefix)(secretKey)
      }
    }
    object json extends NamedProperties(configuration, "accounts.json") {
      lazy val list = names.toSeq.sorted.map { name =>
        val vendor = getStringProperty(name, "vendor", "file")
        val account = getStringProperty(name, "account")
        val resources = getStringPropertiesSplitByComma(name, "resources")
        val url = getStringProperty(name, "url")
        JsonOrigin(vendor, account, url, resources.toSet)
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