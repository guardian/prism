package collectors

import java.time.Instant
import agent._
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import software.amazon.awssdk.services.autoscaling.AutoScalingClient
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{
  DescribeLaunchTemplateVersionsRequest,
  DescribeLaunchTemplatesRequest,
  LaunchTemplateVersion => AwsLaunchTemplateVersion
}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.Try

class LaunchTemplateCollectorSet(accounts: Accounts)
    extends CollectorSet[LaunchTemplateVersion](
      ResourceType("launch-configurations"),
      accounts,
      Some(Regional)
    ) {
  val lookupCollector
      : PartialFunction[Origin, Collector[LaunchTemplateVersion]] = {
    case amazon: AmazonOrigin =>
      AWSLaunchTemplateCollector(
        amazon,
        resource,
        amazon.crawlRate(resource.name)
      )
  }
}

case class AsgLaunchTemplate(id: String, version: String)

case class AWSLaunchTemplateCollector(
    origin: AmazonOrigin,
    resource: ResourceType,
    crawlRate: CrawlRate
) extends Collector[LaunchTemplateVersion]
    with Logging {

  val asgClient: AutoScalingClient = AutoScalingClient.builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  val ec2Client: Ec2Client = Ec2Client.builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  def crawl: Iterable[LaunchTemplateVersion] = {
    val asgs = asgClient.describeAutoScalingGroups().autoScalingGroups().asScala
    val launchTemplates = asgs.map(asg =>
      AsgLaunchTemplate(
        asg.launchTemplate().launchTemplateId(),
        asg.launchTemplate().version()
      )
    )

    launchTemplates.map { lt =>
      // might need to filter out asgs which don't have launch templates?
      val requestLt = DescribeLaunchTemplateVersionsRequest.builder
        .launchTemplateId(lt.id)
        .versions(lt.version)
        .build
      val template = ec2Client
        .describeLaunchTemplateVersions(requestLt)
        .launchTemplateVersions()
        .asScala
        .head
      LaunchTemplateVersion.fromApiData(template, origin)
    }

  }
}

object LaunchTemplateVersion {
  def fromApiData(
      config: AwsLaunchTemplateVersion,
      origin: AmazonOrigin
  ): LaunchTemplateVersion = {
    LaunchTemplateVersion(
      arn = config.launchTemplateId(),
      name = config.launchTemplateName(),
      imageId = config.launchTemplateData().imageId(),
      region = origin.region,
      instanceProfile =
        Option(config.launchTemplateData().iamInstanceProfile().arn()),
      createdTime = Try(config.createTime()).toOption,
      instanceType = config.launchTemplateData().instanceType().toString,
      securityGroups = Option(
        config.launchTemplateData().securityGroups()
      ).toList.flatMap(_.asScala).map { sg =>
        s"arn:aws:ec2:${origin.region}:${origin.accountNumber.get}:security-group/$sg"
      }
    )
  }
}

case class LaunchTemplateVersion(
    arn: String,
    name: String,
    imageId: String,
    region: String,
    instanceProfile: Option[String],
    createdTime: Option[Instant],
    instanceType: String,
    securityGroups: List[String]
) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.launchTemplate(arn)
}
