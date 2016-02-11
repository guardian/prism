package collectors

import agent._
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{Filter, DescribeImagesRequest}
import controllers.routes
import org.joda.time.{DateTime, Duration}
import play.api.mvc.Call
import utils.Logging
import collection.JavaConverters._
import com.amazonaws.services.ec2.model.{Image => AWSImage}

import scala.util.Try

object ImageCollectorSet extends CollectorSet[Image](ResourceType("images", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[Image]] = {
    case amazon:AmazonOrigin => AWSImageCollector(amazon, resource)
  }
}

case class AWSImageCollector(origin:AmazonOrigin, resource:ResourceType) extends Collector[Image] with Logging {

  val client = new AmazonEC2Client(origin.credsProvider)
  client.setRegion(origin.awsRegion)

  def crawl: Iterable[Image] = {
    val result = client.describeImages(new DescribeImagesRequest()
      .withFilters(new Filter("owner-id", Seq(origin.alternativeImageOwner.getOrElse(origin.accountNumber.get)).asJava)))
    result.getImages.asScala.map { Image.fromApiData(_, origin.region) }
  }
}

object Image {
  def arn(region: String, imageId: String) = s"arn:aws:ec2:$region::image/$imageId"

  def fromApiData(image: AWSImage, regionName: String): Image = {
    Image(
      id = arn(regionName, image.getImageId),
      name = image.getName,
      imageId = image.getImageId,
      region = regionName,
      description = image.getDescription,
      tags = image.getTags.asScala.map(t => t.getKey -> t.getValue).toMap,
      creationDate = Try(new DateTime(image.getCreationDate)).toOption,
      state = image.getState,
      architecture = image.getArchitecture,
      ownerId = image.getOwnerId,
      virtualizationType = image.getVirtualizationType,
      hypervisor = image.getHypervisor,
      sriovNetSupport = image.getSriovNetSupport
    )
  }
}

case class Image(
                id: String,
                name: String,
                imageId: String,
                region: String,
                description: String,
                tags: Map[String,String],
                creationDate: Option[DateTime],
                state: String,
                architecture: String,
                ownerId: String,
                virtualizationType: String,
                hypervisor: String,
                sriovNetSupport: String
                  ) extends IndexedItem {
  def callFromId: (String) => Call = id => routes.Api.image(id)
}