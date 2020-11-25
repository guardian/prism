package collectors

import agent._
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.autoscaling.model.{DescribeLaunchConfigurationsRequest, LaunchConfiguration => AwsLaunchConfiguration}
import controllers.routes
import org.joda.time.DateTime
import play.api.mvc.Call
import utils.Logging

import scala.jdk.CollectionConverters._
import conf.AWS

import scala.language.postfixOps
import scala.util.Try

class LaunchConfigurationCollectorSet(accounts: Accounts) extends CollectorSet[LaunchConfiguration](ResourceType("launch-configurations"), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[LaunchConfiguration]] = {
    case amazon: AmazonOrigin => AWSLaunchConfigurationCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSLaunchConfigurationCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[LaunchConfiguration] with Logging {

  val client: AutoScalingClient = AutoScalingClient
    .builder()
    .credentialsProvider(origin.credentials.providerV2)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfigV2)
    .build()

  def crawl: Iterable[LaunchConfiguration] = {
    val request = DescribeLaunchConfigurationsRequest.builder.build
    client.describeLaunchConfigurationsPaginator(request).launchConfigurations().asScala.map(lc =>
      LaunchConfiguration.fromApiData(lc, origin)
    )
  }
}

object LaunchConfiguration {
  def fromApiData(config: AwsLaunchConfiguration, origin: AmazonOrigin): LaunchConfiguration = {
    LaunchConfiguration(
      arn = config.launchConfigurationARN,
      name = config.launchConfigurationName,
      imageId = config.imageId,
      region = origin.region,
      instanceProfile = Option(config.iamInstanceProfile),
      createdTime = Try(new DateTime(config.createdTime)).toOption,
      instanceType = config.instanceType,
      keyName = config.keyName,
      placementTenancy = Option(config.placementTenancy),
      securityGroups = Option(config.securityGroups).toList.flatMap(_.asScala).map { sg =>
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