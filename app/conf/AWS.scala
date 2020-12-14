package conf

import java.net.InetAddress

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeInstancesRequest, DescribeTagsRequest, Filter}
import utils.Logging

import scala.collection.MapView
import scala.jdk.CollectionConverters._
import scala.util.Try

object AWS extends Logging {
  val clientConfig: ClientOverrideConfiguration = ClientOverrideConfiguration
    .builder()
    .retryPolicy(
      RetryPolicy
        .builder()
        .numRetries(10)
        .build()
    )
    .build()

  // This is to detect if we are running in AWS or on GC2. The 169.254.169.254
  // thing works on both but this DNS entry seems peculiar to AWS.
  lazy val isAWS: Boolean = Try(InetAddress.getByName("instance-data")).isSuccess
  def awsOption[T](f: => T): Option[T] = if (isAWS) Option(f) else None

  lazy val connectionRegion: Region = Region.EU_WEST_1

  lazy val EC2Client: Ec2Client = Ec2Client.builder().region(connectionRegion).overrideConfiguration(clientConfig).build()

  type Tag = (String, String)

  object instance {
    lazy val id:Option[String] = awsOption(EC2MetadataUtils.getInstanceId)
    lazy val allTags:Map[String,String] =
      id.toSeq.flatMap { id =>
        val tagsResult = AWS.EC2Client.describeTags(
          DescribeTagsRequest.builder.filters(
            Filter.builder.name("resource-type").values("instance").build,
            Filter.builder.name("resource-id").values(id).build
          ).build
        )
        tagsResult.tags.asScala.map(td => td.key -> td.value)
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
        case(name, value) => Filter.builder.name("tag:" + name).values(value).build
      }.asJavaCollection

      val describeInstancesResult = EC2Client.describeInstances(DescribeInstancesRequest.builder.filters(tagsAsFilters).build)

      val reservation = describeInstancesResult.reservations.asScala.toList
      val instances = reservation.flatMap(r => r.instances.asScala)
      val addresses = instances.flatMap(i => Option(i.privateIpAddress))
      log.info(s"Instances with tags $tags: $addresses")
      addresses
    }
  }

}
