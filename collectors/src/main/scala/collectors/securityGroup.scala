package collectors

import agent._
import conf.AwsClientConfig
import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.{DescribeSecurityGroupsRequest, IpPermission, SecurityGroup => AwsSecurityGroup}

import scala.jdk.CollectionConverters._
import scala.language.{existentials, postfixOps}

class SecurityGroupCollectorSet(accounts: Accounts) extends CollectorSet[SecurityGroup](ResourceType("security-group"), accounts, Some(Regional)) {
  def lookupCollector: PartialFunction[Origin, Collector[SecurityGroup]] = {
    case aws:AmazonOrigin => AWSSecurityGroupCollector(aws, resource, aws.crawlRate(resource.name))
  }
}

case class AWSSecurityGroupCollector(origin:AmazonOrigin, resource:ResourceType, crawlRate: CrawlRate)
    extends Collector[SecurityGroup] {

  def fromAWS( secGroup: AwsSecurityGroup): SecurityGroup = {
    def groupRefs(rule: IpPermission): Seq[SecurityGroupRef] = {
      rule.userIdGroupPairs.asScala.map { pair =>
        SecurityGroupRef(pair.groupId, pair.userId)
      }
    }.toSeq

    val rules = secGroup.ipPermissions.asScala.map { rule =>
      Rule(
        rule.ipProtocol.replace("-1","all"),
        Option(rule.fromPort).map(_.toInt),
        Option(rule.toPort).map(_.toInt),
        rule.ipRanges.asScala.toSeq.map(_.cidrIp).sorted.wrap,
        rule.ipv6Ranges.asScala.toSeq.map(_.cidrIpv6).sorted.wrap,
        groupRefs(rule).wrap
      )
    }

    SecurityGroup(
      s"arn:aws:ec2:${origin.region}:${origin.accountNumber.get}:security-group/${secGroup.groupId}",
      secGroup.groupId,
      secGroup.groupName,
      origin.region,
      rules.toSeq,
      Option(secGroup.vpcId),
      secGroup.tags.asScala.map(t => t.key -> t.value).toMap
    )
  }

  val client: Ec2Client = Ec2Client
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AwsClientConfig.clientConfig)
    .build

  def crawl: Iterable[SecurityGroup] = {
    //  get all existing groups to allow for cross referencing
    val request = DescribeSecurityGroupsRequest.builder.build
    client.describeSecurityGroupsPaginator(request).securityGroups.asScala.map(fromAWS)
  }
}

case class Rule( protocol:String,
                 fromPort:Option[Int],
                 toPort:Option[Int],
                 sourceIpv4CidrBlocks:Option[Seq[String]],
                 sourceIpv6CidrBlocks:Option[Seq[String]],
                 sourceGroupRefs:Option[Seq[SecurityGroupRef]] )

case class SecurityGroup(arn:String,
                         groupId:String,
                         name:String,
                         location:String,
                         rules:Seq[Rule],
                         vpcId:Option[String],
                         tags:Map[String, String]) extends IndexedItem

case class SecurityGroupRef(groupId:String, account:String)
