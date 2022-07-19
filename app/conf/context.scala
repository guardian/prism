package conf

import agent._
import conf.PrismConfiguration.getCrawlRates
import play.api.{Configuration, Mode}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest
import utils.{Logging, UnnaturalOrdering}

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
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

    lazy val prismServerRegion: String = configuration.getOptional[String]("accounts.aws.prismServerRegion").getOrElse(Region.EU_WEST_1.id)

    lazy val allRegions = {
      val ec2Client = Ec2Client.builder.region(Region.EU_WEST_1).build
      try {
        val request = DescribeRegionsRequest.builder.build
        val response = ec2Client.describeRegions(request)
        val regions = response.regions.asScala.toList.map(_.regionName)
        regions
      }
      finally {
        ec2Client.close
      }
    }

    object aws {
      lazy val regionsToCrawl: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.regionsToCrawl").getOrElse(allRegions)
      lazy val highPriorityRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.regionsHighPriority").getOrElse(Seq(prismServerRegion)) :+ Region.AWS_GLOBAL.id
      lazy val crawlRates = getCrawlRates(highPriorityRegions)
      lazy val defaultOwnerId: Option[String] = configuration.getOptional[String]("accounts.aws.defaultOwnerId")
      val list: Seq[AmazonOrigin] = subConfigurations("accounts.aws").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptional[Seq[String]]("regions").getOrElse(regionsToCrawl) :+ Region.AWS_GLOBAL.id
        val accessKey = subConfig.getOptional[String]("accessKey")
        val secretKey = subConfig.getOptional[String]("secretKey")
        val role = subConfig.getOptional[String]("role")
        val ownerId = subConfig.getOptional[String]("ownerId").orElse(defaultOwnerId)
        val profile = subConfig.getOptional[String]("profile")
        val resources = subConfig.getOptional[Seq[String]]("resources").getOrElse(Nil)
        val stagePrefix = subConfig.getOptional[String]("stagePrefix")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, prismServerRegion)(secretKey)
          AmazonOrigin(name, region, resources.toSet, stagePrefix, credentials, ownerId, crawlRates(region))
        }
      }.toList
    }

    object amis {
      lazy val regionsToCrawl: Seq[String] = configuration.getOptional[Seq[String]]("accounts.ami.regionsToCrawl").getOrElse(Seq(prismServerRegion))
      lazy val crawlRates: Map[String, Map[String, CrawlRate]] = getCrawlRates(regionsToCrawl)
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
  val slowCrawlRate: CrawlRate = CrawlRate(1 day, 1 hour)

  val highPriorityRegionCrawlRate: Map[String, CrawlRate] = {
    Map(
      "reservation" -> fastCrawlRate,
      "instance" -> fastCrawlRate,
      "images" -> fastCrawlRate,
      "data" -> fastCrawlRate,
      "cloudformationStacks" -> slowCrawlRate,
    ).withDefaultValue(defaultCrawlRate)
  }
  val lowPriorityRegionCrawlRate: Map[String, CrawlRate] = Map.empty.withDefaultValue(slowCrawlRate)

  def getCrawlRates(highPriorityRegions: Seq[String]): Map[String, Map[String, CrawlRate]] = {
    highPriorityRegions.map(region => (region, highPriorityRegionCrawlRate)).toMap.withDefaultValue(lowPriorityRegionCrawlRate)
  }
}

