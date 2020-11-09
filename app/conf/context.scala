package conf

import utils.{Logging, UnnaturalOrdering}

import scala.language.postfixOps
import play.api.{Configuration, Mode}
import agent._
import java.net.URL

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain

import scala.jdk.CollectionConverters._
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2AsyncClientBuilder
import com.amazonaws.services.ec2.model.DescribeRegionsRequest
import conf.AWS.instance.region
import conf.PrismConfiguration.getCrawlRates

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try
import scala.util.control.NonFatal

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

  def subConfigurations(prefix:String): Map[String,Configuration] = {
    val config = configuration.getOptional[Configuration](prefix).getOrElse(Configuration.empty)
    config.subKeys.flatMap{ subKey =>
      Try(config.getOptional[Configuration](subKey)).getOrElse(None).map(subKey ->)
    }.toMap
  }

  object accounts {
    lazy val lazyStartup: Boolean = configuration.getOptional[String]("accounts.lazyStartup").exists("true" ==)

    lazy val allRegions = {
      val ec2Client = AmazonEC2AsyncClientBuilder.standard().withRegion(Regions.EU_WEST_1).build()
      try {
        val request = new DescribeRegionsRequest() //.withAllRegions(true) - let's add this back in once DeployTools has access to all regions
        val response = ec2Client.describeRegions(request)
        val regions = response.getRegions.asScala.toList.map(_.getRegionName)
        regions
      }
      finally {
        ec2Client.shutdown()
      }
    }

    object aws {
      lazy val regionsToCrawl: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.regionsDefault").getOrElse(allRegions)
      lazy val highPriorityRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.regionsHighPriority").getOrElse(Seq("eu-west-1"))
      lazy val crawlRates = getCrawlRates(highPriorityRegions)
      lazy val defaultOwnerId: Option[String] = configuration.getOptional[String]("accounts.aws.defaultOwnerId")
      val list: Seq[AmazonOrigin] = subConfigurations("accounts.aws").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptional[Seq[String]]("regions").getOrElse(regionsToCrawl)
        val accessKey = subConfig.getOptional[String]("accessKey")
        val secretKey = subConfig.getOptional[String]("secretKey")
        val role = subConfig.getOptional[String]("role")
        val ownerId = subConfig.getOptional[String]("ownerId").orElse(defaultOwnerId)
        val profile = subConfig.getOptional[String]("profile")
        val resources = subConfig.getOptional[Seq[String]]("resources").getOrElse(Nil)
        val stagePrefix = subConfig.getOptional[String]("stagePrefix")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
          AmazonOrigin(name, region, resources.toSet, stagePrefix, credentials, ownerId, crawlRates(region))
        }
      }.toList
    }

    object amis {
      lazy val regionsToCrawl: Seq[String] = configuration.getOptional[Seq[String]]("accounts.ami.regionsDefault").getOrElse(allRegions)
      lazy val highPriorityRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.ami.regionsHighPriority").getOrElse(Seq("eu-west-1"))
      lazy val crawlRates: Map[String, Map[String, CrawlRate]] = getCrawlRates(highPriorityRegions)
      val list: Seq[AmazonOrigin] = subConfigurations("accounts.amis").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptional[Seq[String]]("regions").getOrElse(regionsToCrawl)
        val accessKey = subConfig.getOptional[String]("accessKey")
        val secretKey = subConfig.getOptional[String]("secretKey")
        val role = subConfig.getOptional[String]("role")
        val profile = subConfig.getOptional[String]("profile")
        val accountNumber = subConfig.getOptional[String]("accountNumber")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
          AmazonOrigin.amis(name, region, accountNumber, credentials, ownerId = None, crawlRates(region))
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
          val crawlRates: Map[String, CrawlRate] = Map("data" -> CrawlRate(5 minutes, 1 minute))

          val o = JsonOrigin(vendor, account, url, resources.toSet, crawlRates)
          log.info(s"Parsed $name, got $o")
          o
        } catch {
          case NonFatal(e) =>
            log.warn(s"Failed to process $name", e)
            throw e
        }
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

object PrismConfiguration {
  val fastCrawlRate: CrawlRate = CrawlRate(15 minutes, 1 minute)
  val defaultCrawlRate: CrawlRate = CrawlRate(1 hour, 5 minutes)
  // this crawl rate is for low priority regions - ask Kate/Simon which rates they want for this
  val slowCrawlRate: CrawlRate = CrawlRate(1 day, 1 hour)

  val highPriorityRegionCrawlRate: Map[String, CrawlRate] = {
    Map(
      "reservation" -> fastCrawlRate,
      "instance" -> fastCrawlRate,
      "images" -> fastCrawlRate,
      "data" -> fastCrawlRate
    ).withDefaultValue(defaultCrawlRate)
  }
  val lowPriorityRegionCrawlRate: Map[String, CrawlRate] = Map.empty.withDefaultValue(slowCrawlRate)

  def getCrawlRates(highPriorityRegions: Seq[String]): Map[String, Map[String, CrawlRate]] = {
    highPriorityRegions.map(region => (region, highPriorityRegionCrawlRate)).toMap.withDefaultValue(lowPriorityRegionCrawlRate)
  }
}

