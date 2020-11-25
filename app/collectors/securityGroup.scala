package collectors

import agent._
import software.amazon.awssdk.services.ec2.{Ec2Client}
import software.amazon.awssdk.services.ec2.model.{DescribeSecurityGroupsRequest, IpPermission, SecurityGroup => AwsSecurityGroup}
import conf.AWS
import controllers.{Prism, routes}
import play.api.mvc.Call
import utils.PaginatedAWSRequest

import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class SecurityGroupCollectorSet(accounts: Accounts, prismController: Prism) extends CollectorSet[SecurityGroup](ResourceType("security-group"), accounts) {
  def lookupCollector: PartialFunction[Origin, Collector[SecurityGroup]] = {
    case aws:AmazonOrigin => AWSSecurityGroupCollector(aws, resource, prismController, aws.crawlRate(resource.name))
  }
}

case class AWSSecurityGroupCollector(origin:AmazonOrigin, resource:ResourceType, prismController: Prism, crawlRate: CrawlRate)
    extends Collector[SecurityGroup] {

  def fromAWS( secGroup: AwsSecurityGroup, lookup:Map[String,SecurityGroup]): SecurityGroup = {
    def groupRefs(rule: IpPermission): Seq[SecurityGroupRef] = {
      rule.userIdGroupPairs.asScala.map { pair =>
        SecurityGroupRef(pair.groupId, pair.userId, lookup.get(pair.groupId).map(_.arn))
      }
    }.toSeq

    val rules = secGroup.ipPermissions.asScala.map { rule =>
      Rule(
        rule.ipProtocol.replace("-1","all"),
        Option(rule.fromPort).map(_.toInt),
        Option(rule.toPort).map(_.toInt),
        rule.ipRanges.asScala.toSeq.map(_.toString).sorted.wrap,
        rule.ipv6Ranges.asScala.toSeq.map(_.toString).sorted.wrap,
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
    .builder()
    .credentialsProvider(origin.credentials.providerV2)
    .region(origin.awsRegionV2)
    .overrideConfiguration(AWS.clientConfigV2)
    .build()

  def crawl: Iterable[SecurityGroup] = {
    //  get all existing groups to allow for cross referencing
    val existingGroups = prismController.securityGroupAgent.get().flatMap(_.data).map(sg => sg.groupId -> sg).toMap
    //TODO
    val secGroups = PaginatedAWSRequest.run(client.describeSecurityGroups)(new DescribeSecurityGroupsRequest())
    secGroups.map ( fromAWS(_, existingGroups) )
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
                         tags:Map[String, String]) extends IndexedItem {
  def callFromArn: String => Call = arn => routes.Api.securityGroup(arn)
}

object SecurityGroup {
  implicit val arnLookup = new ArnLookup[SecurityGroup] {
    override def call(arn: String): Call = routes.Api.securityGroup(arn)
    override def item(arn: String, prism: Prism): Option[(Label,SecurityGroup)] =
      prism.securityGroupAgent.getTuples.find(_._2.arn==arn)
  }
}

case class SecurityGroupRef(groupId:String, account:String, arn:Option[String])