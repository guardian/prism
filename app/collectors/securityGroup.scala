package collectors

import org.joda.time.Duration
import play.api.mvc.Call
import com.amazonaws.services.ec2.model.{SecurityGroup => AWSSecurityGroup, IpPermission}
import scala.collection.JavaConversions._
import controllers.{Prism, routes}
import agent._
import com.amazonaws.services.ec2.AmazonEC2Client
import utils.Logging

object SecurityGroupCollectorSet extends CollectorSet[SecurityGroup](ResourceType("security-group", Duration.standardMinutes(15L))) {
  def lookupCollector: PartialFunction[Origin, Collector[SecurityGroup]] = {
    case aws:AmazonOrigin => AWSSecurityGroupCollector(aws, resource)
  }
}

case class AWSSecurityGroupCollector(origin:AmazonOrigin, resource:ResourceType)
    extends Collector[SecurityGroup] {

  def fromAWS( secGroup: AWSSecurityGroup, lookup:Map[String,SecurityGroup]): SecurityGroup = {
    def groupRefs(rule: IpPermission): Seq[SecurityGroupRef] = {
      rule.getUserIdGroupPairs.map { pair =>
        SecurityGroupRef(pair.getGroupId, pair.getUserId, lookup.get(pair.getGroupId).map(_.arn))
      }
    }

    val rules = secGroup.getIpPermissions.map { rule =>
      Rule(
        rule.getIpProtocol.replace("-1","all"),
        Option(rule.getFromPort).map(_.toInt),
        Option(rule.getToPort).map(_.toInt),
        rule.getIpRanges.toSeq.sorted.wrap,
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
      secGroup.getTags.map(t => t.getKey -> t.getValue).toMap
    )
  }

  val client = new AmazonEC2Client(origin.credentials.provider)
  client.setEndpoint(s"ec2.${origin.region}.amazonaws.com")

  def crawl: Iterable[SecurityGroup] = {
    // get all existing groups to allow for cross referencing
    val existingGroups = Prism.securityGroupAgent.get().flatMap(_.data).map(sg => sg.groupId -> sg).toMap
    val secGroups = client.describeSecurityGroups.getSecurityGroups
    secGroups.map ( fromAWS(_, existingGroups) )
  }
}

case class Rule( protocol:String,
                 fromPort:Option[Int],
                 toPort:Option[Int],
                 sourceCidrBlocks:Option[Seq[String]],
                 sourceGroupRefs:Option[Seq[SecurityGroupRef]] )

case class SecurityGroup(arn:String,
                         groupId:String,
                         name:String,
                         location:String,
                         rules:Seq[Rule],
                         vpcId:Option[String],
                         tags:Map[String, String]) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.securityGroup(arn)
}

object SecurityGroup {
  implicit val arnLookup = new ArnLookup[SecurityGroup] {
    override def call(arn: String): Call = routes.Api.securityGroup(arn)
    override def item(arn: String): Option[(Label,SecurityGroup)] =
      Prism.securityGroupAgent.getTuples.find(_._2.arn==arn).headOption
  }
}

case class SecurityGroupRef(groupId:String, account:String, arn:Option[String])