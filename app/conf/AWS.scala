package conf

import java.net.InetAddress

import com.amazonaws.ClientConfiguration
import com.amazonaws.regions.Regions
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.services.ec2.model.{DescribeInstancesRequest, Filter, DescribeTagsRequest => EC2DescribeTagsRequest}
import com.amazonaws.util.EC2MetadataUtils
import utils.Logging

import scala.collection.MapView
import scala.jdk.CollectionConverters._
import scala.util.Try

object AWS extends Logging {

  val clientConfig: ClientConfiguration = new ClientConfiguration().withMaxErrorRetry(10)

  // This is to detect if we are running in AWS or on GC2. The 169.254.169.254
  // thing works on both but this DNS entry seems peculiar to AWS.
  lazy val isAWS: Boolean = Try(InetAddress.getByName("instance-data")).isSuccess
  def awsOption[T](f: => T): Option[T] = if (isAWS) Option(f) else None

  lazy val connectionRegion: Regions = instance.region.getOrElse(Regions.EU_WEST_1)

  lazy val EC2Client: AmazonEC2 = AmazonEC2ClientBuilder.standard().withRegion(connectionRegion).withClientConfiguration(clientConfig).build()

  type Tag = (String, String)

  object instance {
    lazy val id:Option[String] = awsOption(EC2MetadataUtils.getInstanceId)
    lazy val region:Option[Regions] = awsOption {
      Regions.fromName(Regions.getCurrentRegion.getName)
    }
    lazy val allTags:Map[String,String] =
      id.toSeq.flatMap { id =>
        val tagsResult = AWS.EC2Client.describeTags(
          new EC2DescribeTagsRequest().withFilters(
            new Filter("resource-type").withValues("instance"),
            new Filter("resource-id").withValues(id)
          )
        )
        tagsResult.getTags.asScala.map{td => td.getKey -> td.getValue }
      }.toMap
    lazy val customTags: MapView[String, String] = allTags.view.filterKeys(!_.startsWith("aws:"))
    lazy val identity: Option[Identity] = (customTags.get("Stack"), customTags.get("App"), customTags.get("Stage")) match {
      case (Some(stack), Some(app), Some(stage)) => Some(Identity(stack, app, stage))
      case _ => None
    }
  }

  object instanceLookup {

    def addressesFromTags(tags: List[Tag]): List[String] = {

      log.info(s"Looking up instances with tags: $tags")
      val tagsAsFilters = tags.map{
        case(name, value) => new Filter("tag:" + name).withValues(value)
      }.asJavaCollection

      val describeInstancesResult = EC2Client.describeInstances(new DescribeInstancesRequest().withFilters(tagsAsFilters))

      val reservation = describeInstancesResult.getReservations.asScala.toList
      val instances = reservation.flatMap(r => r.getInstances.asScala)
      val addresses = instances.flatMap(i => Option(i.getPrivateIpAddress))
      log.info(s"Instances with tags $tags: $addresses")
      addresses
    }
  }

}
