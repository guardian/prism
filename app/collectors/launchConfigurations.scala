package collectors

import agent._
import com.amazonaws.services.autoscaling.{AmazonAutoScaling, AmazonAutoScalingClient, AmazonAutoScalingClientBuilder}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import scala.jdk.CollectionConverters._
import com.amazonaws.services.autoscaling.model.{DescribeLaunchConfigurationsRequest, LaunchConfiguration => AWSLaunchConfiguration}
import conf.AWS

import scala.util.Try
import scala.concurrent.duration._
import scala.language.postfixOps

class LaunchConfigurationCollectorSet(accounts: Accounts) extends CollectorSet[LaunchConfiguration](ResourceType("launch-configurations"), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[LaunchConfiguration]] = {
    case amazon: AmazonOrigin => AWSLaunchConfigurationCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSLaunchConfigurationCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[LaunchConfiguration] with Logging {

  val client: AmazonAutoScaling = AmazonAutoScalingClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .withClientConfiguration(AWS.clientConfig)
    .build()

  def crawl: Iterable[LaunchConfiguration] = {
    PaginatedAWSRequest.run(client.describeLaunchConfigurations)(new DescribeLaunchConfigurationsRequest()).map(lc => LaunchConfiguration.fromApiData(lc, origin))
  }
}

object LaunchConfiguration {
  def fromApiData(config: AWSLaunchConfiguration, origin: AmazonOrigin): LaunchConfiguration = {
    LaunchConfiguration(
      arn = config.getLaunchConfigurationARN,
      name = config.getLaunchConfigurationName,
      imageId = config.getImageId,
      region = origin.region,
      instanceProfile = Option(config.getIamInstanceProfile),
      createdTime = Try(new DateTime(config.getCreatedTime)).toOption,
      instanceType = config.getInstanceType,
      keyName = config.getKeyName,
      placementTenancy = Option(config.getPlacementTenancy),
      securityGroups = Option(config.getSecurityGroups).toList.flatMap(_.asScala).map { sg =>
        s"arn:aws:ec2:${origin.region}:${origin.accountNumber.get}:security-group/$sg"
      }
    )
  }
}

case class LaunchConfiguration(
  arn: String,
  name: String,
  imageId: String,
  region: String,
  instanceProfile: Option[String],
  createdTime: Option[DateTime],
  instanceType: String,
  keyName: String,
  placementTenancy: Option[String],
  securityGroups: List[String]
) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.launchConfiguration(arn)
}