package conf

// TODO: Figure out what to do about com.gu.management
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

class PrismConfiguration(configuration: Configuration) extends Logging {
  implicit class option2getOrException[T](option: Option[T]) {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  // TODO: I'm not completely sure that this will work but worth a try
  def subConfigurations(prefix:String): Map[String,Configuration] = {
    val config = configuration.getOptional[Configuration](prefix).getOrElse(Configuration.empty)
    config.subKeys.flatMap{ subKey =>
      Try(config.getOptional[Configuration](subKey)).getOrElse(None).map(subKey ->)
    }.toMap
  }

  object accounts {
    lazy val lazyStartup: Boolean = configuration.getOptional[String]("accounts.lazyStartup").exists("true" ==)

    // TODO: Should the values which are Option[String] here actually just be Strings
    object aws {
      lazy val defaultRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.defaultRegions").getOrElse(Seq("eu-west-1"))
      lazy val defaultOwnerId: Option[String] = configuration.getOptional[String]("accounts.aws.defaultOwnerId")
      val list: Seq[AmazonOrigin] = subConfigurations("accounts.aws").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptional[Seq[String]]("regions").getOrElse(defaultRegions)
        val accessKey = subConfig.getOptional[String]("accessKey")
        val secretKey = subConfig.getOptional[String]("secretKey")
        val role = subConfig.getOptional[String]("role")
        val ownerId: Option[String] = subConfig.getOptional[String]("ownerId").orElse(defaultOwnerId)
        val profile: Option[String] = subConfig.getOptional[String]("profile")
        val resources:Seq[String] = subConfig.getOptional[Seq[String]]("resources").getOrElse(Nil)
        val stagePrefix: Option[String] = subConfig.getOptional[String]("stagePrefix")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
          AmazonOrigin(name, region, resources.toSet, stagePrefix, credentials, ownerId)
        }
      }.toList
    }

    object amis {
      lazy val defaultRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.amis.defaultRegions").getOrElse(aws.defaultRegions)
      val list: Seq[AmazonOrigin] = subConfigurations("accounts.amis").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptional[Seq[String]]("regions").getOrElse(defaultRegions)
        val accessKey = subConfig.getOptional[String]("accessKey")
        val secretKey = subConfig.getOptional[String]("secretKey")
        val role = subConfig.getOptional[String]("role")
        val profile = subConfig.getOptional[String]("profile")
        val accountNumber = subConfig.getOptional[String]("accountNumber")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
          AmazonOrigin.amis(name, region, accountNumber, credentials, ownerId = None)
        }
      }.toList
    }

    object json {
      lazy val list: Seq[JsonOrigin] = subConfigurations("accounts.json").map { case (name, config) =>
        log.info(s"processing $name")
        try {
          val vendor = config.getOptional[String]("vendor").getOrElse("file")
          val account = config.getOptional[String]("account").getOrException("Account must be specified")
          val resources = config.getOptional[Seq[String]]("resources").getOrElse(Nil)
          val url = config.getOptional[String]("url").getOrException("URL must be specified")

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
      lazy val list: Seq[GoogleDocOrigin] = subConfigurations("accounts.googleDoc").map { case(name, config) =>
        val url = config.getOptional[String]("url").getOrException("URL must be specified")
        val resources = config.getOptional[Seq[String]]("resources").getOrElse(Nil)
        GoogleDocOrigin(name, new URL(url), resources.toSet)
      }.toList
    }
  }

  object logging {
    lazy val verbose: Boolean = configuration.getOptional[String]("logging").exists(_.equalsIgnoreCase("VERBOSE"))
  }

  object stages {
    lazy val order: List[String] = configuration.getOptional[Seq[String]]("stages.order").getOrElse(Nil).toList.filterNot(""==)
    lazy val ordering: UnnaturalOrdering[String] = UnnaturalOrdering(order)
  }

  object urls {
    lazy val publicPrefix: String = configuration.getOptional[String]("urls.publicPrefix").getOrElse("http://localhost:9000")
  }

  override def toString: String = configuration.toString
}

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