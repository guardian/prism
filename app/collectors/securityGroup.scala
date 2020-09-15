package collectors

import agent._
import com.amazonaws.services.ec2.{AmazonEC2, AmazonEC2ClientBuilder}
import com.amazonaws.services.ec2.model.{DescribeSecurityGroupsRequest, IpPermission, SecurityGroup => AWSSecurityGroup}
import controllers.routes
import play.api.mvc.Call
import utils.PaginatedAWSRequest

import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

object SecurityGroupCollectorSet extends CollectorSet[SecurityGroup](ResourceType("security-group", 1 hour, 5 minutes)) {
  def lookupCollector: PartialFunction[Origin, Collector[SecurityGroup]] = {
    case aws:AmazonOrigin => AWSSecurityGroupCollector(aws, resource)
  }
}

case class AWSSecurityGroupCollector(origin:AmazonOrigin, resource:ResourceType)
    extends Collector[SecurityGroup] {

  def fromAWS( secGroup: AWSSecurityGroup, lookup:Map[String,SecurityGroup]): SecurityGroup = {
    def groupRefs(rule: IpPermission): Seq[SecurityGroupRef] = {
      rule.getUserIdGroupPairs.asScala.map { pair =>
        SecurityGroupRef(pair.getGroupId, pair.getUserId, lookup.get(pair.getGroupId).map(_.arn))
      }
    }.toSeq

    val rules = secGroup.getIpPermissions.asScala.map { rule =>
      Rule(
        rule.getIpProtocol.replace("-1","all"),
        Option(rule.getFromPort).map(_.toInt),
        Option(rule.getToPort).map(_.toInt),
        rule.getIpv4Ranges.asScala.toSeq.map(_.toString).sorted.wrap,
        rule.getIpv6Ranges.asScala.toSeq.map(_.toString).sorted.wrap,
        groupRefs(rule).wrap
      )
    }
    SecurityGroup(
      s"arn:aws:ec2:${origin.region}:${origin.accountNumber.get}:security-group/${secGroup.getGroupId}",
      secGroup.getGroupId,
      secGroup.getGroupName,
      origin.region,
      rules.toSeq,
      Option(secGroup.getVpcId),
      secGroup.getTags.asScala.map(t => t.getKey -> t.getValue).toMap
    )
  }

  val client: AmazonEC2 = AmazonEC2ClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[SecurityGroup] = {
    // get all existing groups to allow for cross referencing
//    val existingGroups = Prism.securityGroupAgent.get().flatMap(_.data).map(sg => sg.groupId -> sg).toMap
//    val secGroups = PaginatedAWSRequest.run(client.describeSecurityGroups)(new DescribeSecurityGroupsRequest())
//    secGroups.map ( fromAWS(_, existingGroups) )
    Seq()
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
  def callFromArn: (String) => Call = arn => routes.Application.index()//routes.Api.securityGroup(arn)
}

object SecurityGroup {
  implicit val arnLookup = new ArnLookup[SecurityGroup] {
    override def call(arn: String): Call = routes.Application.index() //routes.Api.securityGroup(arn)
    override def item(arn: String): Option[(Label,SecurityGroup)] = None
//      Prism.securityGroupAgent.getTuples.find(_._2.arn==arn).headOption
  }
}

case class SecurityGroupRef(groupId:String, account:String, arn:Option[String])