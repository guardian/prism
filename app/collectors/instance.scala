package collectors

import org.joda.time.{Duration, DateTime}
import org.jclouds.ContextBuilder
import org.jclouds.compute.ComputeServiceContext
import scala.collection.JavaConversions._
import utils.Logging
import org.jclouds.openstack.nova.v2_0.NovaApi
import org.jclouds.openstack.nova.v2_0.domain.Server
import java.net.InetAddress
import conf.Configuration.accounts
import play.api.libs.json.Json
import play.api.mvc.Call
import controllers.routes
import scala.language.postfixOps
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{Instance => AWSInstance, Reservation}

object InstanceCollectorSet extends CollectorSet[Instance](ResourceType("instance", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[Instance]] = {
    case json:JsonOrigin => JsonInstanceCollector(json, resource)
    case amazon:AmazonOrigin => AWSInstanceCollector(amazon, resource)
    case openstack:OpenstackOrigin => OSInstanceCollector(openstack, resource)
  }
}

case class JsonInstanceCollector(origin:JsonOrigin, resource:ResourceType) extends JsonCollector[Instance] {
  import jsonimplicits.joda.dateTimeReads
  implicit val addressReads = Json.reads[Address]
  implicit val instanceSpecificationReads = Json.reads[InstanceSpecification]
  implicit val managementEndpointReads = Json.reads[ManagementEndpoint]
  implicit val instanceReads = Json.reads[Instance]
  def crawl: Iterable[Instance] = crawlJson
}

case class AWSInstanceCollector(origin:AmazonOrigin, resource:ResourceType) extends Collector[Instance] with Logging {

  val client = new AmazonEC2Client(origin.creds)
  client.setEndpoint(s"ec2.${origin.region}.amazonaws.com")

  def getInstances:Iterable[(Reservation, AWSInstance)] = {
    client.describeInstances().getReservations.flatMap(r => r.getInstances.map(r -> _))
  }

  def crawl:Iterable[Instance] = {
    getInstances.map { case (reservation, instance) =>
      Instance.fromApiData(
        id = s"arn:aws:ec2:${origin.region}:${origin.accountNumber.getOrElse(reservation.getOwnerId)}:instance/${instance.getInstanceId}",
        vendorState = Some(instance.getState.getName),
        group = instance.getPlacement.getAvailabilityZone,
        addresses = AddressList(
          "public" -> Address(instance.getPublicDnsName, instance.getPublicIpAddress),
          "private" -> Address(instance.getPrivateDnsName, instance.getPrivateIpAddress)
        ),
        createdAt = new DateTime(instance.getLaunchTime),
        instanceName = instance.getInstanceId,
        internalName = instance.getPrivateDnsName,
        region = origin.region,
        vendor = "aws",
        tags = instance.getTags.map(t => t.getKey -> t.getValue).toMap,
        specs = InstanceSpecification(instance.getImageId, instance.getInstanceType)
      )
    }
  }
}

case class OSInstanceCollector(origin:OpenstackOrigin, resource:ResourceType) extends Collector[Instance] with Logging {

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

  def crawl: Iterable[Instance] = {
    getServers.flatMap{ s =>
      val ip = s.getAddresses.asMap.headOption.flatMap(_._2.find(_.getVersion == 4).map(_.getAddr))
      val addressOption = ip.map(Address.fromIp)
      val instanceId = s.getExtendedAttributes.asSet.headOption.map(_.getInstanceName).getOrElse("UNKNOWN").replace("instance", "i")
      if (addressOption.isEmpty) log.warn(s"Ignoring instance $instanceId from $origin as it doesn't have an IP address")
      addressOption.map { address =>
        Instance.fromApiData(
          id = s"arn:openstack:ec2:${origin.region}:${origin.tenant}:instance/$instanceId",
          vendorState = Some(s.getStatus.value),
          group = origin.region,
          addresses = AddressList("private" -> address),
          createdAt = new DateTime(s.getCreated),
          instanceName = instanceId,
          internalName = s.getName, // use dnsname
          region = origin.region,
          vendor = "openstack",
          tags = s.getMetadata.toMap,
          InstanceSpecification(s.getImage.getName, s.getFlavor.getName)
        )
      }
    }.map(origin.transformInstance)
  }
}

object Instance {
  def fromApiData( id: String,
             vendorState: Option[String],
             group: String,
             addresses: AddressList,
             createdAt: DateTime,
             instanceName: String,
             internalName: String,
             region: String,
             vendor: String,
             tags: Map[String, String],
             specs: InstanceSpecification): Instance = {
    val stack = tags.get("Stack")
    val app = tags.get("App").map(_.split(",").toList).getOrElse(Nil)

    apply(
      id = id,
      name = addresses.primary.dnsName,
      vendorState = vendorState,
      group = group,
      dnsName = addresses.primary.dnsName,
      ip = addresses.primary.ip,
      addresses = addresses.mapOfAddresses,
      createdAt = createdAt,
      instanceName = instanceName,
      internalName = internalName,
      region = region,
      vendor = vendor,
      tags = tags,
      stage = tags.get("Stage"),
      stack = stack,
      app = app,
      mainclasses = tags.get("Mainclass").map(_.split(",").toList).orElse(stack.map(stack => app.map(a => s"$stack::$a"))).getOrElse(Nil),
      role = tags.get("Role"),
      management = ManagementEndpoint.fromTag(addresses.primary.dnsName, tags.get("Management")),
      Some(specs)
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

case class AddressList(primary: Address, mapOfAddresses:Map[String,Address])
object AddressList {
  def apply(addresses:(String, Address)*): AddressList = AddressList(addresses.head._2, addresses.toMap)
}

case class Address(dnsName: String, ip: String)
object Address {
  def fromIp(ip:String): Address = Address(InetAddress.getByName(ip).getCanonicalHostName, ip)
  def fromFQDN(dnsName:String): Address = Address(dnsName, InetAddress.getByName(dnsName).getHostAddress)
}

case class InstanceSpecification(image:String, instanceType:String)

case class Instance(
                 id: String,
                 name: String,
                 vendorState: Option[String],
                 group: String,
                 dnsName: String,
                 ip: String,
                 addresses: Map[String,Address],
                 createdAt: DateTime,
                 instanceName: String,
                 internalName: String,
                 region: String,
                 vendor: String,
                 tags: Map[String, String] = Map.empty,
                 stage: Option[String],
                 stack: Option[String],
                 app: List[String],
                 mainclasses: List[String],
                 role: Option[String],
                 management:Option[Seq[ManagementEndpoint]],
                 specification:Option[InstanceSpecification]
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