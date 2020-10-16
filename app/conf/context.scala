package conf

import utils.{UnnaturalOrdering, Logging}
import scala.language.postfixOps
import play.api.{Configuration, Mode}
import agent._
import java.net.URL

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

    object aws {
      lazy val defaultRegions: Seq[String] = configuration.getOptional[Seq[String]]("accounts.aws.defaultRegions").getOrElse(Seq("eu-west-1"))
      lazy val defaultOwnerId: Option[String] = configuration.getOptional[String]("accounts.aws.defaultOwnerId")
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