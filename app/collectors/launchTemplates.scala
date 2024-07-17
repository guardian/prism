package collectors

import java.time.Instant
import agent._
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{
  DescribeLaunchTemplateVersionsRequest,
  DescribeLaunchTemplatesRequest,
  LaunchTemplateVersion => AwsLaunchTemplateVersion,
  LaunchTemplate => AwsLaunchTemplate
}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.Try

class LaunchTemplateCollectorSet(accounts: Accounts)
    extends CollectorSet[LaunchTemplate](
      ResourceType("launch-templates"),
      accounts,
      Some(Regional)
    ) {
  val lookupCollector: PartialFunction[Origin, Collector[LaunchTemplate]] = {
    case amazon: AmazonOrigin =>
      AWSLaunchTemplateCollector(
        amazon,
        resource,
        amazon.crawlRate(resource.name)
      )
  }
}

case class AWSLaunchTemplateCollector(
    origin: AmazonOrigin,
    resource: ResourceType,
    crawlRate: CrawlRate
) extends Collector[LaunchTemplate]
    with Logging {

  val ec2Client: Ec2Client = Ec2Client.builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  def crawl: Iterable[LaunchTemplate] = {

    val launchTemplateRequest = DescribeLaunchTemplatesRequest.builder.build()
    val launchTemplates = ec2Client
      .describeLaunchTemplates(launchTemplateRequest)
      .launchTemplates()
      .asScala
      .toList

    launchTemplates.map { lt =>
      // might need to filter out asgs which don't have launch templates?
      val requestLt = DescribeLaunchTemplateVersionsRequest.builder
        .launchTemplateId(lt.launchTemplateId())
        .build
      val versions = ec2Client
        .describeLaunchTemplateVersions(requestLt)
        .launchTemplateVersions()
        .asScala
        .toList
      LaunchTemplate.fromApiData(lt, versions, origin)
    }

  }
}

object LaunchTemplate {
  def fromApiData(
      template: AwsLaunchTemplate,
      versions: List[AwsLaunchTemplateVersion],
      origin: AmazonOrigin
  ): LaunchTemplate = {
    LaunchTemplate(
      arn = template.launchTemplateId(),
      name = template.launchTemplateName(),
      region = origin.region,
      createdTime = Try(template.createTime()).toOption,
      versions = versions.map(v =>
        LaunchTemplateVersion(
          versionNumber = v.versionNumber(),
          imageId = v.launchTemplateData().imageId(),
          instanceProfile =
            Option(v.launchTemplateData().iamInstanceProfile().arn()),
          createdTime = Try(v.createTime()).toOption,
          instanceType = v.launchTemplateData().instanceType().toString,
          securityGroups = Option(
            v.launchTemplateData().securityGroups()
          ).toList.flatMap(_.asScala).map { sg =>
            s"arn:aws:ec2:${origin.region}:${origin.accountNumber.get}:security-group/$sg"
          }
        )
      )
    )
  }
}

case class LaunchTemplateVersion(
    versionNumber: Long,
    imageId: String,
    instanceProfile: Option[String],
    createdTime: Option[Instant],
    instanceType: String,
    securityGroups: List[String]
)

case class LaunchTemplate(
    arn: String,
    name: String,
    region: String,
    createdTime: Option[Instant],
    versions: List[LaunchTemplateVersion]
) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.launchTemplate(arn)
}
