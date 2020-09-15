package conf

//import com.gu.management.{ManifestPage, CountMetric, TimingMetric, GaugeMetric, Switchboard, HealthcheckManagementPage, StatusPage, PropertiesPage}
import utils.{UnnaturalOrdering, Logging}
import scala.language.postfixOps
import play.api.{Configuration, Mode, Play}
//import com.gu.management.play.{RequestMetrics, Management => GuManagement}
//import com.gu.management.logback.LogbackLevelPage
import agent._
import java.net.URL
//import controllers.Prism

import scala.util.Try
import scala.util.control.NonFatal

//object App {
//  val name: String = if (Play.current.mode == Mode.Test) "prism-test" else "prism"
//}

trait ConfigurationSource {
  def configuration(mode: Mode): Configuration
}

//class PrismConfiguration(configuration: Configuration) extends Logging {
//  implicit class option2getOrException[T](option: Option[T]) {
//    def getOrException(exceptionMessage: String): T = {
//      option.getOrElse {
//        throw new IllegalStateException(exceptionMessage)
//      }
//    }
//  }
//
//  def subConfigurations(prefix:String): Map[String,Configuration] = {
//
//    val config = configuration.underlying.getConfig(prefix)
//    config.subKeys.flatMap{ subKey =>
//      Try(config.getConfig(subKey)).getOrElse(None).map(subKey ->)
//    }.toMap
//  }
//
//  object accounts {
//    lazy val lazyStartup = configuration.getString("accounts.lazyStartup").exists("true" ==)
//
//    object aws {
//      lazy val defaultRegions = configuration.getStringSeq("accounts.aws.defaultRegions").getOrElse(Seq("eu-west-1"))
//      lazy val defaultOwnerId = configuration.getString("accounts.aws.defaultOwnerId")
//      val list: Seq[AmazonOrigin] = subConfigurations("accounts.aws").flatMap{ case (name, subConfig) =>
//        val regions = subConfig.getStringSeq("regions").getOrElse(defaultRegions)
//        val accessKey = subConfig.getString("accessKey")
//        val secretKey = subConfig.getString("secretKey")
//        val role = subConfig.getString("role")
//        val ownerId = subConfig.getString("ownerId").orElse(defaultOwnerId)
//        val profile = subConfig.getString("profile")
//        val resources:Seq[String] = subConfig.getStringSeq("resources").getOrElse(Nil)
//        val stagePrefix = subConfig.getString("stagePrefix")
//        regions.map { region =>
//          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
//          AmazonOrigin(name, region, resources.toSet, stagePrefix, credentials, ownerId)
//        }
//      }.toList
//    }
//
//    object amis {
//      lazy val defaultRegions = configuration.getStringSeq("accounts.amis.defaultRegions").getOrElse(aws.defaultRegions)
//      val list: Seq[AmazonOrigin] = subConfigurations("accounts.amis").flatMap{ case (name, subConfig) =>
//        val regions = subConfig.getStringSeq("regions").getOrElse(defaultRegions)
//        val accessKey = subConfig.getString("accessKey")
//        val secretKey = subConfig.getString("secretKey")
//        val role = subConfig.getString("role")
//        val profile = subConfig.getString("profile")
//        val accountNumber = subConfig.getString("accountNumber")
//        regions.map { region =>
//          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
//          AmazonOrigin.amis(name, region, accountNumber, credentials, ownerId = None)
//        }
//      }.toList
//    }
//
//    object json {
//      lazy val list = subConfigurations("accounts.json").map { case (name, config) =>
//        log.info(s"processing $name")
//        try {
//          val vendor = config.getString("vendor").getOrElse("file")
//          val account = config.getString("account").getOrException("Account must be specified")
//          val resources = config.getStringSeq("resources").getOrElse(Nil)
//          val url = config.getString("url").getOrException("URL must be specified")
//
//          val o = JsonOrigin(vendor, account, url, resources.toSet)
//          log.info(s"Parsed $name, got $o")
//          o
//        } catch {
//          case NonFatal(e) =>
//            log.warn(s"Failed to process $name", e)
//            throw e
//        }
//      }.toList
//    }
//    object googleDoc {
//      lazy val list = subConfigurations("accounts.googleDoc").map { case(name, config) =>
//        val url = config.getString("url").getOrException("URL must be specified")
//        val resources = config.getStringSeq("resources").getOrElse(Nil)
//        GoogleDocOrigin(name, new URL(url), resources.toSet)
//      }.toList
//    }
//  }
//
//  object logging {
//    lazy val verbose = configuration.getString("logging").exists(_.equalsIgnoreCase("VERBOSE"))
//  }
//
//  object stages {
//    lazy val order = configuration.getStringSeq("stages.order").getOrElse(Nil).toList.filterNot(""==)
//    lazy val ordering = UnnaturalOrdering(order, aliensAtEnd = true)
//  }
//
//  object urls {
//    lazy val publicPrefix: String = configuration.getString("urls.publicPrefix").getOrElse("http://localhost:9000")
//  }
//
//  override def toString: String = configuration.toString
//}
//
//object PrismConfiguration extends PrismConfiguration()

//object PlayRequestMetrics extends RequestMetrics.Standard

//object SourceMetrics {
//  def sources = CollectorAgent.sources.data
//  object TotalGauge extends GaugeMetric("prism", "sources", "Sources", "Number of sources in Prism", () => sources.size)
//  object SuccessGauge extends GaugeMetric("prism", "success_sources", "Successful Sources", "Number of sources in Prism that last ran successfully", () => sources.count(_.error.isEmpty), Some(TotalGauge))
//  object ErrorGauge extends GaugeMetric("prism", "error_sources", "Erroring Sources", "Number of sources in Prism that are failing to run", () => sources.count(_.error.isDefined), Some(TotalGauge))
//
//  object CrawlTimer extends TimingMetric("prism", "crawl", "Crawls", "Attempted crawls of sources")
//  object CrawlSuccessCounter extends CountMetric("prism", "crawl_success", "Successful crawls", "Number of crawls that succeeded")
//  object CrawlFailureCounter extends CountMetric("prism", "crawl_error", "Failed crawls", "Number of crawls that failed")
//  val all = Seq(TotalGauge, SuccessGauge, ErrorGauge, CrawlTimer, CrawlSuccessCounter, CrawlFailureCounter)
//}

//object DataMetrics extends Logging {
//  val resourceNames = Prism.allAgents.flatMap(_.resourceName).distinct
//  def countResources(resource:String) = {
//    val filteredAgents = Prism.allAgents.filter{ _.resourceName.contains(resource) }
//    filteredAgents.map(_.size).sum
//  }
//  val resourceGauges = resourceNames.map { resource =>
//    new GaugeMetric("prism", s"${resource}_entities", s"$resource entities", s"Number of $resource entities", () => countResources(resource))
//  }
//}

//object Management extends GuManagement {
//  val applicationName = App.name
//
//  lazy val pages = List(
//    new ManifestPage(),
//    new Switchboard(applicationName, Seq()),
//    new HealthcheckManagementPage,
//    StatusPage(applicationName, PlayRequestMetrics.asMetrics ++ SourceMetrics.all ++ DataMetrics.resourceGauges),
//    new PropertiesPage(Configuration.toString),
//    new LogbackLevelPage(applicationName)
//  )
//}