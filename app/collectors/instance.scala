package collectors

import org.joda.time.{Duration, DateTime}
import org.jclouds.ContextBuilder
import org.jclouds.compute.ComputeServiceContext
import scala.collection.JavaConversions._
import org.jclouds.aws.ec2.AWSEC2Api
import org.jclouds.ec2.domain.{Reservation, RunningInstance}
import utils.Logging
import org.jclouds.openstack.nova.v2_0.NovaApi
import org.jclouds.openstack.nova.v2_0.domain.Server
import java.net.InetAddress
import conf.Configuration.accounts
import play.api.libs.json.Json
import play.api.mvc.Call
import controllers.routes
import scala.language.postfixOps

object InstanceCollectorSet extends CollectorSet[Instance](ResourceType("instance", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[Instance]] = {
    case json:JsonOrigin => JsonInstanceCollector(json, resource)
    case amazon:AmazonOrigin => AWSInstanceCollector(amazon, resource)
    case openstack:OpenstackOrigin => OSInstanceCollector(openstack, resource)
  }
}

case class JsonInstanceCollector(origin:JsonOrigin, resource:ResourceType) extends JsonCollector[Instance] {
  import jsonimplicits.joda.dateTimeReads
  implicit val managementEndpointReads = Json.reads[ManagementEndpoint]
  implicit val instanceReads = Json.reads[Instance]
  def crawl: Iterable[Instance] = crawlJson
}

case class AWSInstanceCollector(origin:AmazonOrigin, resource:ResourceType) extends Collector[Instance] with Logging {

  lazy val context = ContextBuilder.newBuilder("aws-ec2")
    .credentials(origin.accessKey, origin.secretKey)
    .build(classOf[ComputeServiceContext])
  lazy val compute = context.getComputeService

  lazy val awsClient = ContextBuilder.newBuilder("aws-ec2").credentials(origin.accessKey, origin.secretKey).buildApi(classOf[AWSEC2Api])
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

  def crawl:Iterable[Instance] = {
    getReservationInstances.map { case (reservation, instance) =>
      Instance.fromApiData(
        id = s"arn:aws:ec2:${origin.region}:${reservation.getOwnerId}:instance/${instance.getId}",
        name = instance.getDnsName,
        vendorState = Some(instance.getInstanceState.value),
        group = instance.getAvailabilityZone,
        dnsName = instance.getDnsName,
        createdAt = new DateTime(instance.getLaunchTime),
        instanceName = instance.getId,
        internalName = instance.getPrivateDnsName,
        region = origin.region,
        vendor = "aws",
        account = reservation.getOwnerId,
        accountName = origin.account,
        tags = instance.getTags.toMap
      )
    }
  }
}

case class OSInstanceCollector(origin:OpenstackOrigin, resource:ResourceType) extends Collector[Instance] {

  lazy val context = ContextBuilder.newBuilder("openstack-nova")
    .endpoint(origin.endpoint)
    .credentials(s"${origin.tenant}:${origin.user}", origin.secret)
    .build(classOf[ComputeServiceContext])
  lazy val compute = context.getComputeService

  lazy val novaApi = ContextBuilder.newBuilder("openstack-nova")
    .endpoint(origin.endpoint)
    .credentials(s"${origin.tenant}:${origin.user}", origin.secret)
    .buildApi(classOf[NovaApi])

  def getServers: Iterable[Server] = {
    val instances = novaApi.getServerApiForZone(origin.region).listInDetail().flatten
    instances.map{
      case i:Server => i
    }
  }

  def getDnsFQDN(server:Server): String = {
    val ip = server.getAddresses.asMap.head._2.filter(_.getVersion == 4).head.getAddr
    InetAddress.getByName(ip).getCanonicalHostName
  }

  def crawl: Iterable[Instance] = {
    getServers.map{ s =>
      val dnsName = getDnsFQDN(s)
      val instanceId = s.getExtendedAttributes.asSet.headOption.map(_.getInstanceName).getOrElse("UNKNOWN").replace("instance", "i")
      Instance.fromApiData(
        id = s"arn:openstack:ec2:${origin.region}:${origin.tenant}:instance/$instanceId",
        name = dnsName,
        vendorState = Some(s.getStatus.value),
        group = origin.region,
        dnsName = dnsName,
        createdAt = new DateTime(s.getCreated),
        instanceName = instanceId,
        internalName = s.getName, // use dnsname
        region = origin.region,
        account = origin.tenant,
        accountName = origin.tenant,
        vendor = "openstack",
        tags = s.getMetadata.toMap
      )
    }.map(origin.transformInstance)
  }
}

object Instance {
  def fromApiData( id: String,
             name: String,
             vendorState: Option[String],
             group: String,
             dnsName: String,
             createdAt: DateTime,
             instanceName: String,
             internalName: String,
             region: String,
             account: String,
             accountName: String,
             vendor: String,
             tags: Map[String, String] ): Instance = {
    val stack = tags.get("Stack")
    val app = tags.get("App").map(_.split(",").toList).getOrElse(Nil)

    apply(
      id = id,
      name = name,
      vendorState = vendorState,
      group = group,
      dnsName = dnsName,
      createdAt = createdAt,
      instanceName = instanceName,
      internalName = internalName,
      region = region,
      account = account,
      accountName = accountName,
      vendor = vendor,
      tags = tags,
      stage = tags.get("Stage"),
      stack = stack,
      app = app,
      mainclasses = tags.get("Mainclass").map(_.split(",").toList).orElse(stack.map(stack => app.map(a => s"$stack::$a"))).getOrElse(Nil),
      role = tags.get("Role"),
      management = ManagementEndpoint.fromTag(dnsName, tags.get("Management"))
    )
  }
}

case class ManagementEndpoint(protocol:String, port:Int, path:String, url:String, format:String, source:String)
object ManagementEndpoint {
  val KeyValue = """([^=]*)=(.*)""".r
  def fromTag(dnsName:String, tag:Option[String]): Option[Seq[ManagementEndpoint]] = {
    tag match {
      case Some("none") => None
      case Some(tagContent) =>
        Some(tagContent.split(";").filterNot(_.isEmpty).map{ endpoint =>
          val params = endpoint.split(",").filterNot(_.isEmpty).flatMap {
            case KeyValue(key,value) => Some(key -> value)
            case _ => None
          }.toMap
          fromMap(dnsName, params)
        })
      case None => Some(Seq(fromMap(dnsName)))
    }
  }
  def fromMap(dnsName:String, map:Map[String,String] = Map.empty):ManagementEndpoint = {
    val protocol = map.getOrElse("protocol","http")
    val port = map.get("port").map(_.toInt).getOrElse(18080)
    val path = map.getOrElse("path","/management")
    val url = s"$protocol://$dnsName:$port$path"
    val source: String = if (map.isEmpty) "convention" else "tag"
    ManagementEndpoint(protocol, port, path, url, map.getOrElse("format", "gu"), source)
  }
}

case class Instance(
                 id: String,
                 name: String,
                 vendorState: Option[String],
                 group: String,
                 dnsName: String,
                 createdAt: DateTime,
                 instanceName: String,
                 internalName: String,
                 region: String,
                 account: String,
                 accountName: String,
                 vendor: String,
                 tags: Map[String, String] = Map.empty,
                 stage: Option[String],
                 stack: Option[String],
                 app: List[String],
                 mainclasses: List[String],
                 role: Option[String],
                 management:Option[Seq[ManagementEndpoint]]
                ) extends IndexedItem {

  def callFromId: (String) => Call = id => routes.Api.instance(id)
  override lazy val fieldIndex: Map[String, String] = super.fieldIndex ++ Map("dnsName" -> dnsName) ++ stage.map("stage" ->)

  def +(other:Instance):Instance = {
    this.copy(
      mainclasses = (this.mainclasses ++ other.mainclasses).distinct,
      app = (this.app ++ other.app).distinct,
      tags = this.tags ++ other.tags
    )
  }

  def prefixStage(prefix:String):Instance = {
    this.copy(stage = stage.map(s => s"$prefix$s"))
  }
}