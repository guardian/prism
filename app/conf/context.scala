package conf

import com.gu.conf.ConfigurationFactory
import utils.{UnnaturalOrdering, Logging}
import scala.language.postfixOps
import play.api.{Mode, Play}
import com.gu.management._
import com.gu.management.play.{RequestMetrics, Management => GuManagement}
import com.gu.management.logback.LogbackLevelPage
import collectors._
import java.net.URL
import collectors.GoogleDocOrigin
import scala.Some
import collectors.OpenstackOrigin
import collectors.JsonOrigin
import collectors.AmazonOrigin
import controllers.Prism

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
    lazy val all = aws.list ++ openstack.list ++ json.list ++ googleDoc.list
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
    object googleDoc extends NamedProperties(configuration, "accounts.googleDoc") {
      lazy val list = names.toSeq.sorted.map { name =>
        val url = getStringProperty(name, "url")
        val resources = getStringPropertiesSplitByComma(name, "resources")
        GoogleDocOrigin(name, new URL(url), resources.toSet)
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

object SourceMetrics {
  def sources = CollectorAgent.sources.data
  object TotalGauge extends GaugeMetric("prism", "sources", "Sources", "Number of sources in Prism", () => sources.size)
  object SuccessGauge extends GaugeMetric("prism", "success_sources", "Successful Sources", "Number of sources in Prism that last ran successfully", () => sources.count(_.error.isEmpty), Some(TotalGauge))
  object ErrorGauge extends GaugeMetric("prism", "error_sources", "Erroring Sources", "Number of sources in Prism that are failing to run", () => sources.count(_.error.isDefined), Some(TotalGauge))

  object CrawlTimer extends TimingMetric("prism", "crawl", "Crawls", "Attempted crawls of sources")
  object CrawlSuccessCounter extends CountMetric("prism", "crawl_success", "Successful crawls", "Number of crawls that succeeded")
  object CrawlFailureCounter extends CountMetric("prism", "crawl_error", "Failed crawls", "Number of crawls that failed")
  val all = Seq(TotalGauge, SuccessGauge, ErrorGauge, CrawlTimer, CrawlSuccessCounter, CrawlFailureCounter)
}

object DataMetrics extends Logging {
  val resourceNames = Prism.allAgents.flatMap(_.resourceName).distinct
  def countResources(resource:String) = {
    val filteredAgents = Prism.allAgents.filter{ _.resourceName == Some(resource) }
    filteredAgents.map(_.size).fold(0)(_+_)
  }
  val resourceGauges = resourceNames.map { resource =>
    new GaugeMetric("prism", s"${resource}_entities", s"$resource entities", s"Number of $resource entities", () => countResources(resource))
  }
}

object Management extends GuManagement {
  val applicationName = App.name

  lazy val pages = List(
    new ManifestPage(),
    new Switchboard(applicationName, Seq()),
    new HealthcheckManagementPage,
    StatusPage(applicationName, PlayRequestMetrics.asMetrics ++ SourceMetrics.all ++ DataMetrics.resourceGauges),
    new PropertiesPage(Configuration.toString),
    new LogbackLevelPage(applicationName)
  )
}