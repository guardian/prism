package collectors

import agent._
import com.amazonaws.services.autoscaling.{AmazonAutoScalingClient, AmazonAutoScalingClientBuilder}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import collection.JavaConverters._
import com.amazonaws.services.autoscaling.model.{DescribeLaunchConfigurationsRequest, LaunchConfiguration => AWSLaunchConfiguration}

import scala.util.Try

import scala.concurrent.duration._
import scala.language.postfixOps

case class AWSLaunchConfigurationCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[LaunchConfiguration] with Logging {

  val client = AmazonAutoScalingClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
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

  implicit val fields = new Fields[LaunchConfiguration] {
    override def defaultFields: Seq[String] = Seq("name", "imageId", "createdTime")
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

object LaunchConfigurationCollectorSet extends CollectorSet[LaunchConfiguration](ResourceType("launch-configurations", 1 hour, 5 minutes)) {
  val lookupCollector: PartialFunction[Origin, Collector[LaunchConfiguration]] = {
    case amazon: AmazonOrigin => AWSLaunchConfigurationCollector(amazon, resource)
  }
}
