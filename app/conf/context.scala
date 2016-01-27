package conf

import com.gu.management.{ManifestPage, CountMetric, TimingMetric, GaugeMetric, Switchboard, HealthcheckManagementPage, StatusPage, PropertiesPage}
import utils.{UnnaturalOrdering, Logging}
import scala.language.postfixOps
import play.api.{Configuration, Mode, Play}
import com.gu.management.play.{RequestMetrics, Management => GuManagement}
import com.gu.management.logback.LogbackLevelPage
import agent._
import java.net.URL
import controllers.Prism

import scala.util.Try
import scala.util.control.NonFatal

object App {
  val name: String = if (Play.current.mode == Mode.Test) "prism-test" else "prism"
}

trait ConfigurationSource {
  def configuration(mode: Mode.Mode): Configuration
}

class PrismConfiguration() extends Logging {
  val configuration = Play.current.configuration

  implicit class option2getOrException[T](option: Option[T]) {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  def subConfigurations(prefix:String): Map[String,Configuration] = {
    val config = configuration.getConfig(prefix).getOrElse(Configuration.empty)
    config.subKeys.flatMap{ subKey =>
      Try(config.getConfig(subKey)).getOrElse(None).map(subKey ->)
    }.toMap
  }

  object accounts {
    lazy val lazyStartup = configuration.getString("accounts.lazyStartup").exists("true" ==)

    object aws {
      lazy val defaultRegion = configuration.getString("accounts.aws.defaultRegion").getOrElse("eu-west-1")
      val list = subConfigurations("accounts.aws").map{ case (name, subConfig) =>
        val region = subConfig.getString("region").getOrElse(defaultRegion)
        val accessKey = subConfig.getString("accessKey")
        val secretKey = subConfig.getString("secretKey")
        val role = subConfig.getString("role")
        val profile = subConfig.getString("profile")
        val resources:Seq[String] = subConfig.getStringSeq("resources").getOrElse(Nil)
        val stagePrefix = subConfig.getString("stagePrefix")
        AmazonOrigin(name, region, accessKey, role, profile, resources.toSet, stagePrefix, secretKey)
      }.toList
    }

    object json {
      lazy val list = subConfigurations("accounts.json").map { case (name, config) =>
        log.info(s"processing $name")
        try {
          val vendor = config.getString("vendor").getOrElse("file")
          val account = config.getString("account").getOrException("Account must be specified")
          val resources = config.getStringSeq("resources").getOrElse(Nil)
          val url = config.getString("url").getOrException("URL must be specified")

          val o = JsonOrigin(vendor, account, url, resources.toSet)
          log.info(s"Parsed $name, got $o")
          o
        } catch {
          case NonFatal(e) =>
            log.warn(s"Failed to process $name", e)
            throw e
        }
      }.toList
    }
    object googleDoc {
      lazy val list = subConfigurations("accounts.googleDoc").map { case(name, config) =>
        val url = config.getString("url").getOrException("URL must be specified")
        val resources = config.getStringSeq("resources").getOrElse(Nil)
        GoogleDocOrigin(name, new URL(url), resources.toSet)
      }.toList
    }
  }

  object logging {
    lazy val verbose = configuration.getString("logging").exists(_.equalsIgnoreCase("VERBOSE"))
  }

  object stages {
    lazy val order = configuration.getStringSeq("stages.order").getOrElse(Nil).toList.filterNot(""==)
    lazy val ordering = UnnaturalOrdering(order, aliensAtEnd = true)
  }

  object urls {
    lazy val publicPrefix: String = configuration.getString("urls.publicPrefix").getOrElse("http://localhost:9000")
  }

  override def toString: String = configuration.toString
}

object PrismConfiguration extends PrismConfiguration()

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