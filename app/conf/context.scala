package conf

import utils.{Logging, UnnaturalOrdering}

import scala.language.postfixOps
import play.api.{Configuration, Mode}
import agent._
import java.net.URL

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain

import scala.jdk.CollectionConverters._
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.AmazonEC2AsyncClientBuilder
import com.amazonaws.services.ec2.model.DescribeRegionsRequest
import conf.AWS.instance.region

import scala.concurrent.duration.DurationInt
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

    val allRegions = {
      val ec2Client = AmazonEC2AsyncClientBuilder.standard().withRegion(Regions.EU_WEST_1).build()
      try {
        val request = new DescribeRegionsRequest()
        val response = ec2Client.describeRegions(request)
        val regions = response.getRegions.asScala.toList.map(_.getRegionName)
        regions
      }
      finally {
        ec2Client.shutdown()
      }
    }

    val fastCrawl: CrawlRate = CrawlRate(15 minutes, 1 minute)
    val slowCrawl: CrawlRate = CrawlRate(1 hour, 5 minutes)
    val defaultRegions: Seq[String] = configuration.get[Seq[String]]("accounts.aws.regions")

    // TODO: I need help with this mega map, I'm not sure what it should look like
    // given a region, give me a Map[resource, CrawlRate]
    // we have vals for crawl rates
    // we could have vals for priorityRegions and non-priority-slow-crawl regions

    val amazonCrawlRatesPerRegion: Map[String, Map[String, CrawlRate]] = {
      region match {
        case ???
      }
    }

    object aws {
      // lazy val crawlRate: Map[String, Int] = configuration.get[Map[String, Int]]("accounts.aws.crawlRate")
      lazy val defaultRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.regions").getOrElse(allRegions)
      lazy val defaultOwnerId: Option[String] = configuration.getOptional[String]("accounts.aws.defaultOwnerId")
      lazy val crawlRates: Map[String, Map[String, CrawlRate]] = Map("eu-west-1" -> Map("instance" -> CrawlRate(5 minutes, 1 minute))) // config.getOrElse(our massive map)
      val list: Seq[AmazonOrigin] = subConfigurations("accounts.aws").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptional[Seq[String]]("regions").getOrElse(defaultRegions)
        val accessKey = subConfig.getOptional[String]("accessKey")
        val secretKey = subConfig.getOptional[String]("secretKey")
        val role = subConfig.getOptional[String]("role")
        val ownerId = subConfig.getOptional[String]("ownerId").orElse(defaultOwnerId)
        val profile = subConfig.getOptional[String]("profile")
        val resources = subConfig.getOptional[Seq[String]]("resources").getOrElse(Nil)
        val stagePrefix = subConfig.getOptional[String]("stagePrefix")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
          // for each region, we create an AmazonOrigin
          AmazonOrigin(name, region, resources.toSet, stagePrefix, credentials, ownerId, crawlRates)
        }
      }.toList
    }

    object amis {
      lazy val defaultRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.regions").getOrElse(allRegions)
      lazy val crawlRates: Map[String, Map[String, CrawlRate]] = Map("eu-west-1" -> Map("ami" -> CrawlRate(5 minutes, 1 minute))) // config.getOrElse(our massive map)
      val list: Seq[AmazonOrigin] = subConfigurations("accounts.amis").flatMap{ case (name, subConfig) =>
        val regions = subConfig.getOptional[Seq[String]]("regions").getOrElse(defaultRegions)
        val accessKey = subConfig.getOptional[String]("accessKey")
        val secretKey = subConfig.getOptional[String]("secretKey")
        val role = subConfig.getOptional[String]("role")
        val profile = subConfig.getOptional[String]("profile")
        val accountNumber = subConfig.getOptional[String]("accountNumber")
        regions.map { region =>
          val credentials = Credentials(accessKey, role, profile, region)(secretKey)
          AmazonOrigin.amis(name, region, accountNumber, credentials, ownerId = None, crawlRates)
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
          val crawlRates: Map[String, Map[String, CrawlRate]] = Map("eu-west-1" -> Map("data" -> CrawlRate(5 minutes, 1 minute)))

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