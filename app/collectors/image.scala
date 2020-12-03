package collectors

import agent._
import conf.AWS
import controllers.routes
import org.joda.time.DateTime
import play.api.mvc.Call
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeImagesRequest, Filter, Image => AwsImage}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.Try

class ImageCollectorSet(accounts:Accounts) extends CollectorSet[Image](ResourceType("images"), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[Image]] = {
    case amazon:AmazonOrigin => AWSImageCollector(amazon, resource, amazon.crawlRate(resource.name))
  }

  override def awsRegionType: Option[AwsRegionType] = Some(Regional)
}

case class AWSImageCollector(origin:AmazonOrigin, resource:ResourceType, crawlRate: CrawlRate) extends Collector[Image] with Logging {

  val client: Ec2Client = Ec2Client
    .builder()
    .credentialsProvider(origin.credentials.providerV2)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfigV2)
    .build()

  def crawl: Iterable[Image] = {
    val ownerIdFilter = Filter.builder().name("owner-id").values(origin.accountNumber.get).build()
    val imageTypeFilter = Filter.builder().name("image-type").values("machine").build()
    val result = client.describeImages(DescribeImagesRequest.builder
      .filters(
        ownerIdFilter,
        imageTypeFilter
      )
      .build)
    result.images.asScala.map(Image.fromApiData(_, origin.region))
  }
}

object Image {
  def arn(region: String, imageId: String) = s"arn:aws:ec2:$region::image/$imageId"

  def fromApiData(image: AwsImage, regionName: String): Image = {
    Image(
      arn = arn(regionName, image.imageId),
      name = Option(image.name),
      imageId = image.imageId,
      region = regionName,
      description = Option(image.description),
      tags = image.tags.asScala.map(t => t.key -> t.value).toMap,
      creationDate = Try(new DateTime(image.creationDate)).toOption,
      state = image.stateAsString,
      architecture = image.architectureAsString,
      ownerId = image.ownerId,
      virtualizationType = image.virtualizationTypeAsString,
      hypervisor = image.hypervisorAsString,
      sriovNetSupport = Option(image.sriovNetSupport),
      rootDeviceType = image.rootDeviceTypeAsString,
      imageType = image.imageTypeAsString
    )
  }
}

case class Image(
                arn: String,
                name: Option[String],
                imageId: String,
                region: String,
                description: Option[String],
                tags: Map[String,String],
                creationDate: Option[DateTime],
                state: String,
                architecture: String,
                ownerId: String,
                virtualizationType: String,
                hypervisor: String,
                sriovNetSupport: Option[String],
                rootDeviceType: String,
                imageType: String
                  ) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.image(arn)
}