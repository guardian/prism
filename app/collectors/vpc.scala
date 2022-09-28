package collectors

import agent._
import conf.AWS
import controllers.routes
import play.api.mvc.Call
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeSubnetsRequest, DescribeVpcsRequest, Filter, Subnet => AwsSubnet, Vpc => AwsVpc}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.Try
import software.amazon.awssdk.services.ec2.model.DescribeRouteTablesRequest

class VpcCollectorSet(accounts: Accounts) extends CollectorSet[Vpc](ResourceType("Vpc"), accounts, Some(Regional)) {
  val lookupCollector: PartialFunction[Origin, Collector[Vpc]] = {
    case amazon:AmazonOrigin => AWSVpcCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class AWSVpcCollector(origin:AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Vpc] with Logging {

  val client: Ec2Client = Ec2Client
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfig)
    .build

  def getSubnets(vpcId: String): Iterable[AwsSubnet] = {
    val subnetsRequest = DescribeSubnetsRequest.builder.filters(Filter.builder.name("vpc-id").values(vpcId).build).build
    client.describeSubnetsPaginator(subnetsRequest).subnets.asScala
  }

  def getSubnetScopes(vpcId: String): Map[String, SubnetScope] = {
    val req = DescribeRouteTablesRequest.builder().filters(Filter.builder().name("vpc-id").values(vpcId).build).build
    val tables = client.describeRouteTablesPaginator(req).routeTables().asScala

    val m = tables.toList.flatMap(table => {
      // Associations link a route table to other resources, such as subnets,
      // internet gateways, or nats.
      val assocs = table.associations().asScala

      val subnetInfo = for {
        // Discard route tables that do not have an explicit subnet association.
        subnetAssoc <- assocs.find(a => Option(a.subnetId()).isDefined)

        // Public subnets have route tables with an associated internet gateway.
        // These always have IDs prefixed with 'igw'. It feels hacky but it's
        // not clear if there is a better way to detect these. See e.g.
        // https://stackoverflow.com/questions/48830793/aws-vpc-identify-private-and-public-subnet.
        gatewayAssoc <- assocs.find(a => Option(a.gatewayId()).isDefined)
        scope = if (gatewayAssoc.gatewayId().startsWith("igw")) Public else Private
      } yield (subnetAssoc.subnetId() -> scope)

      subnetInfo.toList
    })

    m.toMap
  }

  def crawl:Iterable[Vpc] =
    client.describeVpcsPaginator(DescribeVpcsRequest.builder.build).vpcs.asScala.map { vpc =>
      Vpc.fromApiData(vpc, getSubnets(vpc.vpcId), getSubnetScopes(vpc.vpcId), origin)
    }
}

sealed trait SubnetScope
case object Public extends SubnetScope
case object Private extends SubnetScope
case object Unknown extends SubnetScope

object Vpc {
  val UNUSABLE_IPS_IN_CIDR_BLOCK = 5

  def countFromCidr(cidr: String): Option[Long] = {
    cidr.split("/").tail.headOption.flatMap { mask =>
      val hostBits = 32 - mask.toInt
      Try {
        math.pow(2, hostBits).toLong - UNUSABLE_IPS_IN_CIDR_BLOCK
      }.toOption
    }
  }

  def arn(region: String, accountNumber: String, vpcId: String) = s"arn:aws:ec2:$region:$accountNumber:vpc/$vpcId"

  def fromApiData(vpc: AwsVpc, subnets: Iterable[AwsSubnet], subnetScopes: Map[String, SubnetScope], origin: AmazonOrigin): Vpc = Vpc(
    arn = arn(origin.region, vpc.ownerId, vpc.vpcId),
    vpcId = vpc.vpcId,
    accountId = vpc.ownerId,
    state = vpc.stateAsString,
    cidrBlock = vpc.cidrBlock,
    default = vpc.isDefault,
    tenancy = vpc.instanceTenancyAsString,
    subnets = subnets.toList.map{s =>
      Subnet(
        s.subnetArn,
        s.availabilityZone,
        s.cidrBlock,
        s.stateAsString,
        s.subnetId,
        s.ownerId,
        s.availableIpAddressCount,
        capacityIpAddressCount = countFromCidr(s.cidrBlock),
        s.tags.asScala.map(t => t.key -> t.value).toMap,
        isPublic = subnetScopes.getOrElse(s.subnetId(), Unknown) == Public,
      )
    },
    availableIpAddressSum = subnets.map(_.availableIpAddressCount.toLong).sum,
    capacityIpAddressSum = {
      val counts = subnets.map(_.cidrBlock).flatMap(countFromCidr)
      if (counts.nonEmpty) Some(counts.sum) else None
    },
    tags = vpc.tags.asScala.map(t => t.key -> t.value).toMap
  )
}

case class Subnet(
                   subnetArn: String,
                   availabilityZone: String,
                   cidrBlock: String,
                   state: String,
                   subnetId: String,
                   ownerId: String,
                   availableIpAddressCount: Int,
                   capacityIpAddressCount: Option[Long],
                   tags: Map[String, String] = Map.empty,
                   isPublic: Boolean, // Whether the Subnet is public or private, where public means it has an internet gateway in its route table.
                 )

case class Vpc(
                arn: String,
                vpcId: String,
                accountId: String,
                state: String,
                cidrBlock: String,
                default: Boolean,
                tenancy: String,
                subnets: List[Subnet],
                availableIpAddressSum: Long,
                capacityIpAddressSum: Option[Long],
                tags: Map[String, String] = Map.empty,
              ) extends IndexedItemWithStage with IndexedItemWithStack {
  def callFromArn: String => Call = arn => routes.Api.vpcs(arn)
}

