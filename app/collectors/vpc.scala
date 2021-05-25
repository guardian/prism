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

  def getSubnets(vpcId: String) = {
    val subnetsRequest = DescribeSubnetsRequest.builder.filters(Filter.builder.name("vpc-id").values(vpcId).build).build
    client.describeSubnetsPaginator(subnetsRequest).subnets.asScala
  }

  def crawl:Iterable[Vpc] =
    client.describeVpcsPaginator(DescribeVpcsRequest.builder.build).vpcs.asScala.map { vpc =>
      Vpc.fromApiData(vpc, getSubnets(vpc.vpcId), origin)
    }
}

object Vpc {
  def countFromCidr(cidr: String): Option[Long] = {
    cidr.split("/").tail.headOption.flatMap { mask =>
      val hostBits = 32 - mask.toInt
      Try(math.pow(2, hostBits).toLong).toOption
    }
  }

  def arn(region: String, accountNumber: String, vpcId: String) = s"arn:aws:ec2:$region:$accountNumber:vpc/$vpcId"

  def fromApiData(vpc: AwsVpc, subnets: Iterable[AwsSubnet], origin: AmazonOrigin): Vpc = Vpc(
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
        countFromCidr(s.cidrBlock),
        s.tags.asScala.map(t => t.key -> t.value).toMap
      )
    },
    availableIpAddressSum = subnets.map(_.availableIpAddressCount.toInt).sum,
    cidrIpAddressSize = countFromCidr(vpc.cidrBlock),
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
                   cidrIpAddressSize: Option[Long],
                   tags: Map[String, String] = Map.empty,
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
                availableIpAddressSum: Int,
                cidrIpAddressSize: Option[Long],
                tags: Map[String, String] = Map.empty,
              ) extends IndexedItemWithStage with IndexedItemWithStack {
  def callFromArn: String => Call = arn => routes.Api.vpcs(arn)
}

