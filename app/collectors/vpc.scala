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

case class RouteTable(isMain: Boolean, subnetIDs: Set[String], hasInternetGateway: Boolean)
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

  // Returns a map of subnetId to scope (public or private).
  def getSubnetScopes(vpcId: String, subnets: List[AwsSubnet]): Map[String, SubnetScope] = {
    val req = DescribeRouteTablesRequest.builder().filters(Filter.builder().name("vpc-id").values(vpcId).build).build
    val tablesData = client.describeRouteTablesPaginator(req).routeTables().asScala

    // Let's convert the AWS data into something more useful for our purposes.
    val tables = tablesData.map(table => {
      val assocs = table.associations().asScala.toList
      val routes = table.routes().asScala.toList

      val isMain = assocs.exists(assoc => assoc.main())

      // It feels like there should be a better way to detect the presence of an AWS Internet Gateway but apparently this is it :(.
      val tableHasIgw = routes.exists(route => Option(route.gatewayId()).getOrElse("").startsWith("igw"))
      val subnetIDs = assocs.flatMap(assoc => Option(assoc.subnetId()).toList)
      RouteTable(isMain = isMain, hasInternetGateway = tableHasIgw, subnetIDs = subnetIDs.toSet)
    })

    val data = subnets.map(subnet => {
      // If there is no explicit route table associated with a subnet, the VPC 'main' route table is used instead.
      val main = tables.find(table => table.isMain)
      val associatedTable = tables.find(table => table.subnetIDs.contains(subnet.subnetId))

      val isPublic = associatedTable.orElse(main).exists(table => table.hasInternetGateway)
      val scope = if (isPublic) Public else Private

      (subnet.subnetId -> scope)
    })

    data.toMap
  }

  def crawl:Iterable[Vpc] =
    client.describeVpcsPaginator(DescribeVpcsRequest.builder.build).vpcs.asScala.map { vpc =>
      val subnets = getSubnets(vpc.vpcId).toList
      Vpc.fromApiData(vpc, subnets, getSubnetScopes(vpc.vpcId, subnets), origin)
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

