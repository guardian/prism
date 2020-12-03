package collectors

import java.net.InetAddress
import java.time.Instant

import agent._
import conf.AWS
import controllers.{Prism, routes}
import play.api.mvc.Call
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeInstancesRequest, Instance => AwsInstance, Reservation => AwsReservation}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.matching.Regex

class InstanceCollectorSet(accounts: Accounts, prism: Prism) extends CollectorSet[Instance](ResourceType("instance"), accounts, Some(Regional)) {
  val lookupCollector: PartialFunction[Origin, Collector[Instance]] = {
    case amazon:AmazonOrigin => AWSInstanceCollector(amazon, resource, amazon.crawlRate(resource.name), prism)
  }
}

case class AWSInstanceCollector(origin:AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate, prism: Prism) extends Collector[Instance] with Logging {

  val client: Ec2Client = Ec2Client
    .builder()
    .credentialsProvider(origin.credentials.providerV2)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfigV2)
    .build()

  def getInstances:Iterable[(AwsReservation, AwsInstance)] = {
    val reservations = client.describeInstancesPaginator(DescribeInstancesRequest.builder.build).reservations.asScala
    for {
      reservation <- reservations
      instance <- reservation.instances.asScala
    } yield (reservation, instance)
  }

  def crawl:Iterable[Instance] = {
    getInstances.map { case (reservation, instance) =>
      Instance.fromApiData(
        arn = s"arn:aws:ec2:${origin.region}:${origin.accountNumber.getOrElse(reservation.ownerId)}:instance/${instance.instanceId}",
        vendorState = Some(instance.state.nameAsString),
        group = instance.placement.availabilityZone,
        addresses = AddressList(
          "public" -> Address(instance.publicDnsName, instance.publicIpAddress),
          "private" -> Address(instance.privateDnsName, instance.privateIpAddress)
        ),
        createdAt = instance.launchTime,
        instanceName = instance.instanceId,
        region = origin.region,
        vendor = "aws",
        securityGroups = instance.securityGroups.asScala.map{ sg =>
          Reference[SecurityGroup](
            s"arn:aws:ec2:${origin.region}:${origin.accountNumber.get}:security-group/${sg.groupId}",
            Map(
              "groupId" -> sg.groupId,
              "groupName" -> sg.groupName
            ),
            prism
          )
        }.toSeq,
        tags = instance.tags.asScala.map(t => t.key -> t.value).toMap,
        specs = InstanceSpecification(instance.imageId, Image.arn(origin.region, instance.imageId), instance.instanceTypeAsString, Option(instance.vpcId))
      )
    }.map(origin.transformInstance)
  }
}

object Instance {
  def fromApiData( arn: String,
             vendorState: Option[String],
             group: String,
             addresses: AddressList,
             createdAt: Instant,
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
  val KeyValue: Regex = """([^=]*)=(.*)""".r
  def fromTag(dnsName:String, tag:Option[String]): Option[Seq[ManagementEndpoint]] = {
    tag match {
      case Some("none") => None
      case Some(tagContent) =>
        Some(tagContent.split(";").filterNot(_.isEmpty).toIndexedSeq.map{ endpoint =>
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
    val filteredAddresses = addresses.filterNot { case (_, address) =>
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
                 createdAt: Instant,
                 instanceName: String,
                 region: String,
                 vendor: String,
                 securityGroups: Seq[Reference[SecurityGroup]],
                 tags: Map[String, String] = Map.empty,
                 override val stage: Option[String],
                 override val stack: Option[String],
                 app: List[String],
                 mainclasses: List[String],
                 role: Option[String],
                 management:Option[Seq[ManagementEndpoint]],
                 specification:Option[InstanceSpecification]
                ) extends IndexedItemWithStage with IndexedItemWithStack {

  def callFromArn: String => Call = arn => routes.Api.instance(arn)
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