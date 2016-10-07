package collectors

import org.joda.time.{Duration, DateTime}
import scala.collection.JavaConversions._
import utils.Logging
import java.net.InetAddress
import conf.PrismConfiguration.accounts
import play.api.libs.json.Json
import play.api.mvc.Call
import controllers.routes
import scala.language.postfixOps
import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.{Instance => AWSInstance, Reservation => AWSReservation}
import agent._

object InstanceCollectorSet extends CollectorSet[Instance](ResourceType("instance", Duration.standardMinutes(15L))) {
  val lookupCollector: PartialFunction[Origin, Collector[Instance]] = {
    case json:JsonOrigin => JsonInstanceCollector(json, resource)
    case amazon:AmazonOrigin => AWSInstanceCollector(amazon, resource)
  }
}

case class JsonInstanceCollector(origin:JsonOrigin, resource:ResourceType) extends JsonCollector[Instance] {
  import jsonimplicits.joda.dateTimeReads
  import jsonimplicits.model._
  implicit val addressReads = Json.reads[Address]
  implicit val instanceSpecificationReads = Json.reads[InstanceSpecification]
  implicit val managementEndpointReads = Json.reads[ManagementEndpoint]
  implicit val instanceReads = Json.reads[Instance]
  def crawl: Iterable[Instance] = crawlJson
}

case class AWSInstanceCollector(origin:AmazonOrigin, resource:ResourceType) extends Collector[Instance] with Logging {

  val client = new AmazonEC2Client(origin.credentials.provider)
  client.setEndpoint(s"ec2.${origin.region}.amazonaws.com")

  def getInstances:Iterable[(AWSReservation, AWSInstance)] = {
    client.describeInstances().getReservations.flatMap(r => r.getInstances.map(r -> _))
  }

  def crawl:Iterable[Instance] = {
    getInstances.map { case (reservation, instance) =>
      Instance.fromApiData(
        arn = s"arn:aws:ec2:${origin.region}:${origin.accountNumber.getOrElse(reservation.getOwnerId)}:instance/${instance.getInstanceId}",
        vendorState = Some(instance.getState.getName),
        group = instance.getPlacement.getAvailabilityZone,
        addresses = AddressList(
          "public" -> Address(instance.getPublicDnsName, instance.getPublicIpAddress),
          "private" -> Address(instance.getPrivateDnsName, instance.getPrivateIpAddress)
        ),
        createdAt = new DateTime(instance.getLaunchTime),
        instanceName = instance.getInstanceId,
        region = origin.region,
        vendor = "aws",
        securityGroups = instance.getSecurityGroups.map{ sg =>
          Reference[SecurityGroup](
            s"arn:aws:ec2:${origin.region}:${origin.accountNumber.get}:security-group/${sg.getGroupId}",
            Map(
              "groupId" -> sg.getGroupId,
              "groupName" -> sg.getGroupName
            )
          )
        },
        tags = instance.getTags.map(t => t.getKey -> t.getValue).toMap,
        specs = InstanceSpecification(instance.getImageId, Image.arn(origin.region, instance.getImageId), instance.getInstanceType, Option(instance.getVpcId))
      )
    }.map(origin.transformInstance)
  }
}

object Instance {
  def fromApiData( arn: String,
             vendorState: Option[String],
             group: String,
             addresses: AddressList,
             createdAt: DateTime,
             instanceName: String,
             region: String,
             vendor: String,
             securityGroups: Seq[Reference[SecurityGroup]],
             tags: Map[String, String],
             specs: InstanceSpecification): Instance = {
    val stack = tags.get("Stack")
    val app = tags.get("App").map(_.split(",").toList).getOrElse(Nil)

    apply(
      arn = arn,
      name = addresses.primary.dnsName,
      vendorState = vendorState,
      group = group,
      dnsName = addresses.primary.dnsName,
      ip = addresses.primary.ip,
      addresses = addresses.mapOfAddresses,
      createdAt = createdAt,
      instanceName = instanceName,
      region = region,
      vendor = vendor,
      securityGroups = securityGroups,
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
  def apply(addresses:(String, Address)*): AddressList = {
    val filteredAddresses = addresses.filterNot { case (addressName, address) =>
      address.dnsName == null || address.ip == null || address.dnsName.isEmpty || address.ip.isEmpty
    }
    AddressList(
      filteredAddresses.headOption.map(_._2).getOrElse(Address.empty),
      filteredAddresses.toMap
    )
  }
}

case class Address(dnsName: String, ip: String)
object Address {
  def fromIp(ip:String): Address = Address(InetAddress.getByName(ip).getCanonicalHostName, ip)
  def fromFQDN(dnsName:String): Address = Address(dnsName, InetAddress.getByName(dnsName).getHostAddress)
  val empty: Address = Address(null, null)
}

case class InstanceSpecification(imageId:String, imageArn:String, instanceType:String, vpcId:Option[String] = None)

case class Instance(
                 arn: String,
                 name: String,
                 vendorState: Option[String],
                 group: String,
                 dnsName: String,
                 ip: String,
                 addresses: Map[String,Address],
                 createdAt: DateTime,
                 instanceName: String,
                 region: String,
                 vendor: String,
                 securityGroups: Seq[Reference[SecurityGroup]],
                 tags: Map[String, String] = Map.empty,
                 stage: Option[String],
                 stack: Option[String],
                 app: List[String],
                 mainclasses: List[String],
                 role: Option[String],
                 management:Option[Seq[ManagementEndpoint]],
                 specification:Option[InstanceSpecification]
                ) extends IndexedItem {

  def callFromArn: (String) => Call = arn => routes.Api.instance(arn)
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