package collectors

import agent._
import com.amazonaws.services.autoscaling.{AmazonAutoScalingClient, AmazonAutoScalingClientBuilder}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging

import collection.JavaConverters._
import com.amazonaws.services.autoscaling.model.{DescribeLaunchConfigurationsRequest, LaunchConfiguration => AWSLaunchConfiguration}

import scala.util.Try

object LaunchConfigurationCollectorSet extends CollectorSet[LaunchConfiguration](ResourceType("launch-configurations", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[LaunchConfiguration]] = {
    case amazon: AmazonOrigin => AWSLaunchConfigurationCollector(amazon, resource)
  }
}

case class AWSLaunchConfigurationCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[LaunchConfiguration] with Logging {

  val client = AmazonAutoScalingClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawlWithToken(nextToken: Option[String]): Iterable[LaunchConfiguration] = {
    val request = new DescribeLaunchConfigurationsRequest().withNextToken(nextToken.orNull)
    val result = client.describeLaunchConfigurations(request)
    val configs = result.getLaunchConfigurations.asScala.map {
      LaunchConfiguration.fromApiData(_, origin)
    }
    Option(result.getNextToken) match {
      case None => configs
      case Some(token) => configs ++ crawlWithToken(Some(token))
    }
  }

  def crawl: Iterable[LaunchConfiguration] = crawlWithToken(None)
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