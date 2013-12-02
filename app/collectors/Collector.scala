package collectors

import org.joda.time.DateTime
import org.jclouds.{View, ContextBuilder}
import org.jclouds.compute.ComputeServiceContext
import scala.collection.JavaConversions._
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.aws.ec2.AWSEC2Client
import org.jclouds.ec2.domain.{Reservation, RunningInstance}

object InstanceCollector {
  def apply(origin:Origin): InstanceCollector = {
    origin match {
      case amazon:AmazonOrigin => AWSInstanceCollector(amazon)
      case openstack:OpenstackOrigin => OSInstanceCollector(openstack)
    }
  }
}

trait InstanceCollector extends Collector {
  def product: Product = ProductName("instance")
}

case class AWSInstanceCollector(origin:AmazonOrigin) extends InstanceCollector {

  lazy val context = ContextBuilder.newBuilder("aws-ec2")
    .credentials(origin.accessKey, origin.secretKey)
    .build(classOf[ComputeServiceContext])
  lazy val compute = context.getComputeService

  lazy val awsClient = org.jclouds.ContextBuilder.newBuilder("aws-ec2").credentials(origin.accessKey, origin.secretKey).buildApi(classOf[AWSEC2Client])
  lazy val instanceApi = awsClient.getInstanceApi.get()

  var instances: Seq[Instance] = Seq()

  def poll() {
  }

  def getReservationInstances:Iterable[(Reservation[RunningInstance],RunningInstance)] = {
    val nodes = instanceApi.describeInstancesInRegion(origin.region)
    nodes.flatMap {
      case r:Reservation[RunningInstance] =>
        r.map {
          case i:RunningInstance =>
            (r, i)
        }
    }
  }

  def getInstancesViaAbstraction:Iterable[Instance] = {
    val nodes = compute.listNodes()
    nodes.map{ node =>
      val instance = getUnderlyingInstance(node.getProviderId)
      Instance(
        id = s"arn:aws:ec2:${node.getLocation.getParent.getId}:${origin.account}:instance/${node.getProviderId}",
        name = instance.getDnsName,
        group = node.getLocation.getId,
        dnsName = instance.getDnsName,
        createdAt = new DateTime(instance.getLaunchTime),
        instanceName = node.getProviderId,
        internalName = instance.getPrivateDnsName,
        region = node.getLocation.getParent.getId,
        vendor = "aws",
        account = origin.account,
        tags = node.getUserMetadata.toMap
      )
    }
  }

  def getUnderlyingInstance(providerId:String):RunningInstance = {
    instanceApi.describeInstancesInRegion(origin.region, providerId).head match {
      case r:Reservation[RunningInstance] => r.head match {
        case i:RunningInstance => i
      }
    }
  }

  def getInstances:Iterable[Instance] = {
    getReservationInstances.map { case (reservation, instance) =>
      Instance(
        id = s"arn:aws:ec2:${origin.region}:${reservation.getOwnerId}:instance/${instance.getId}",
        name = instance.getDnsName,
        group = instance.getAvailabilityZone,
        dnsName = instance.getDnsName,
        createdAt = new DateTime(instance.getLaunchTime),
        instanceName = instance.getId,
        internalName = instance.getPrivateDnsName,
        region = origin.region,
        vendor = "aws",
        account = reservation.getOwnerId,
        tags = instance.getTags.toMap
      )
    }
  }

  def findPublicDns(node:NodeMetadata, view:View):String = {
    val reservation = instanceApi.describeInstancesInRegion(null, node.getProviderId).head
    val instance:RunningInstance = reservation.head
    instance.getDnsName
  }

}


case class OSInstanceCollector(origin:OpenstackOrigin) extends InstanceCollector {
  def get: Instance = ???
}

object Prism {
  def collectors: Set[Collector] = ???
}

object Instance {
  def apply( id: String,
             name: String,
             group: String,
             dnsName: String,
             createdAt: DateTime,
             instanceName: String,
             internalName: String,
             region: String,
             account: String,
             vendor: String,
             tags: Map[String, String] ): Instance =
     apply(
       id = id,
       name = name,
       group = group,
       dnsName = dnsName,
       createdAt = createdAt,
       instanceName = instanceName,
       internalName = internalName,
       region = region,
       account = account,
       vendor = vendor,
       tags = tags,
       mainclasses = tags.get("Mainclass").map(_.split(",").toList).getOrElse(Nil),
       stage = tags.get("Stage"),
       role = tags.get("Role"),
       stack = tags.get("Stack"),
       apps = tags.get("Apps").map(_.split(",").toList).getOrElse(Nil)
    )
}

case class Instance(
                 id: String,
                 name: String,
                 mainclasses: List[String],
                 stage: Option[String],
                 group: String,
                 role: Option[String],
                 stack: Option[String],
                 apps: List[String],
                 dnsName: String,
                 createdAt: DateTime,
                 instanceName: String,
                 internalName: String,
                 region: String,
                 account: String,
                 vendor: String,
                 tags: Map[String, String] = Map.empty
                 ) {
  def +(other:Instance):Instance = {
    this.copy(
      mainclasses = (this.mainclasses ++ other.mainclasses).distinct,
      apps = (this.apps ++ other.apps).distinct,
      tags = this.tags ++ other.tags
    )
  }
}