package collectors

import agent._
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.services.ec2.model.{DescribeImagesRequest, Filter, Image => AWSImage}
import controllers.routes
import org.joda.time.DateTime
import play.api.mvc.Call
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class ImageCollectorSet(accounts:Accounts) extends CollectorSet[Image](ResourceType("images", 15 minutes, 1 minute), accounts) {
  val lookupCollector: PartialFunction[Origin, Collector[Image]] = {
    case amazon:AmazonOrigin => AWSImageCollector(amazon, resource)
  }
}

case class AWSImageCollector(origin:AmazonOrigin, resource:ResourceType) extends Collector[Image] with Logging {

  val client: AmazonEC2 = AmazonEC2ClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[Image] = {
    val result = client.describeImages(new DescribeImagesRequest()
      .withFilters(
        new Filter("owner-id", Seq(origin.accountNumber.get).asJava),
        new Filter("image-type", Seq("machine").asJava)
      )
    )
    result.getImages.asScala.map { Image.fromApiData(_, origin.region) }
  }
}

object Image {
  def arn(region: String, imageId: String) = s"arn:aws:ec2:$region::image/$imageId"

  def fromApiData(image: AWSImage, regionName: String): Image = {
    Image(
      arn = arn(regionName, image.getImageId),
      name = Option(image.getName),
      imageId = image.getImageId,
      region = regionName,
      description = Option(image.getDescription),
      tags = image.getTags.asScala.map(t => t.getKey -> t.getValue).toMap,
      creationDate = Try(new DateTime(image.getCreationDate)).toOption,
      state = image.getState,
      architecture = image.getArchitecture,
      ownerId = image.getOwnerId,
      virtualizationType = image.getVirtualizationType,
      hypervisor = image.getHypervisor,
      sriovNetSupport = Option(image.getSriovNetSupport),
      rootDeviceType = image.getRootDeviceType,
      imageType = image.getImageType
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