package conf

import agent._
import com.typesafe.config.{Config, ConfigFactory}
import conf.PrismConfiguration.getCrawlRates
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
  def configuration(isTest: Boolean): Config
}

class PrismConfiguration(configuration: Config) extends Logging {
  implicit class RichConfig(c: Config) {
    def getOptionalString(key: String): Option[String] = if (c.hasPath(key)) Some(c.getString(key)) else None
    def getOptionalSeqString(key: String): Option[Seq[String]] = if (c.hasPath(key)) Some(c.getStringList(key).asScala.toSeq) else None
    def subConfigurations(prefix:String): Map[String, Config] = {
      val config: Config = Try(c.getConfig(prefix)).getOrElse(ConfigFactory.empty)
      config.root.keySet.asScala.flatMap{ subKey =>
        Try(config.getConfig(subKey)).toOption.map(subKey -> _)
      }.toMap
    }
  }

  implicit class option2getOrException[T](option: Option[T]) {
    def getOrException(exceptionMessage: String): T = {
      option.getOrElse {
        throw new IllegalStateException(exceptionMessage)
      }
    }
  }

  object collectionStore {
    lazy val bucketName: String = configuration.getString("collectionStore.bucketName")
  }

  object accounts {
    lazy val lazyStartup: Boolean = configuration.getOptionalString("accounts.lazyStartup").exists("true" ==)

    lazy val prismServerRegion: String = configuration.getOptionalString("accounts.aws.prismServerRegion").getOrElse(Region.EU_WEST_1.id)

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
      lazy val regionsToCrawl: Seq[String] = configuration.getOptionalSeqString("accounts.aws.regionsToCrawl").getOrElse(allRegions)
      lazy val highPriorityRegions: Seq[String] = configuration.getOptionalSeqString("accounts.aws.regionsHighPriority").getOrElse(Seq(prismServerRegion)) :+ Region.AWS_GLOBAL.id
      lazy val crawlRates = getCrawlRates(highPriorityRegions)
      lazy val defaultOwnerId: Option[String] = configuration.getOptionalString("accounts.aws.defaultOwnerId")
      val list: Seq[AmazonOrigin] = configuration.subConfigurations("accounts.aws").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptionalSeqString("regions").getOrElse(regionsToCrawl) :+ Region.AWS_GLOBAL.id
        val accessKey = subConfig.getOptionalString("accessKey")
        val secretKey = subConfig.getOptionalString("secretKey")
        val role = subConfig.getOptionalString("role")
        val ownerId = subConfig.getOptionalString("ownerId").orElse(defaultOwnerId)
        val profile = subConfig.getOptionalString("profile")
        val resources = subConfig.getOptionalSeqString("resources").getOrElse(Nil)
        val stagePrefix = subConfig.getOptionalString("stagePrefix")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, prismServerRegion)(secretKey)
          AmazonOrigin(name, region, resources.toSet, stagePrefix, credentials, ownerId, crawlRates(region))
        }
      }.toList
    }

    object amis {
      lazy val regionsToCrawl: Seq[String] = configuration.getOptionalSeqString("accounts.ami.regionsToCrawl").getOrElse(Seq(prismServerRegion))
      lazy val crawlRates: Map[String, Map[String, CrawlRate]] = getCrawlRates(regionsToCrawl)
      val list: Seq[AmazonOrigin] = configuration.subConfigurations("accounts.amis").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptionalSeqString("regions").getOrElse(regionsToCrawl)
        val accessKey = subConfig.getOptionalString("accessKey")
        val secretKey = subConfig.getOptionalString("secretKey")
        val role = subConfig.getOptionalString("role")
        val profile = subConfig.getOptionalString("profile")
        val accountNumber = subConfig.getOptionalString("accountNumber")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
          AmazonOrigin.amis(name, region, accountNumber, credentials, ownerId = None, crawlRates(region))
        }
      }.toList
    }

    object json {
      lazy val list: Seq[JsonOrigin] = configuration.subConfigurations("accounts.json").map { case (name, config) =>
        log.info(s"processing $name")
        try {
          val vendor = config.getOptionalString("vendor").getOrElse("file")
          val account = config.getOptionalString("account").getOrException("Account must be specified")
          val resources = config.getOptionalSeqString("resources").getOrElse(Nil)
          val url = config.getOptionalString("url").getOrException("URL must be specified")
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
    lazy val verbose: Boolean = configuration.getOptionalString("logging").exists(_.equalsIgnoreCase("VERBOSE"))
  }

  object stages {
    lazy val order: List[String] = configuration.getOptionalSeqString("stages.order").getOrElse(Nil).toList.filterNot(""==)
    lazy val ordering: UnnaturalOrdering[String] = UnnaturalOrdering(order)
  }

  object urls {
    lazy val publicPrefix: String = configuration.getOptionalString("urls.publicPrefix").getOrElse("http://localhost:9000")
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
      "data" -> fastCrawlRate
    ).withDefaultValue(defaultCrawlRate)
  }
  val lowPriorityRegionCrawlRate: Map[String, CrawlRate] = Map.empty.withDefaultValue(slowCrawlRate)

  def getCrawlRates(highPriorityRegions: Seq[String]): Map[String, Map[String, CrawlRate]] = {
    highPriorityRegions.map(region => (region, highPriorityRegionCrawlRate)).toMap.withDefaultValue(lowPriorityRegionCrawlRate)
  }
}

