package collectors

import agent._
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging
import collection.JavaConverters._
import com.amazonaws.services.autoscaling.model.{LaunchConfiguration => AWSLaunchConfiguration, DescribeLaunchConfigurationsRequest}

import scala.util.Try

object LaunchConfigurationCollectorSet extends CollectorSet[LaunchConfiguration](ResourceType("launch-configurations", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[LaunchConfiguration]] = {
    case amazon:AmazonOrigin => AWSLaunchConfigurationCollector(amazon, resource)
  }
}

case class AWSLaunchConfigurationCollector(origin:AmazonOrigin, resource:ResourceType) extends Collector[LaunchConfiguration] with Logging {

  val client = new AmazonAutoScalingClient(origin.credentials.provider)
  client.setRegion(origin.awsRegion)

  def crawlWithToken(nextToken: Option[String]): Iterable[LaunchConfiguration] = {
    val request = new DescribeLaunchConfigurationsRequest().withNextToken(nextToken.orNull)
    val result = client.describeLaunchConfigurations(request)
    val configs = result.getLaunchConfigurations.asScala.map { LaunchConfiguration.fromApiData(_, origin.region) }
    Option(result.getNextToken) match {
      case None => configs
      case Some(token) => configs ++ crawlWithToken(Some(token))
    }
  }

  def crawl: Iterable[LaunchConfiguration] = crawlWithToken(None)
}

object LaunchConfiguration {
  def fromApiData(config: AWSLaunchConfiguration, regionName: String): LaunchConfiguration = {
    LaunchConfiguration(
      arn = config.getLaunchConfigurationARN,
      name = config.getLaunchConfigurationName,
      imageId = config.getImageId,
      region = regionName,
      instanceProfile = Option(config.getIamInstanceProfile),
      createdTime = Try(new DateTime(config.getCreatedTime)).toOption,
      instanceType = config.getInstanceType,
      keyName = config.getKeyName,
      placementTenancy = Option(config.getPlacementTenancy),
      securityGroups = Option(config.getSecurityGroups).map(_.asScala).toList.flatten,
      userData = Option(config.getUserData)
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
                  securityGroups: List[String],
                  userData: Option[String]
                  ) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.launchConfiguration(arn)
}