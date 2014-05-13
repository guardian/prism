package collectors

import org.joda.time.Duration
import play.api.mvc.Call
import org.jclouds.ContextBuilder
import org.jclouds.openstack.nova.v2_0.domain.{SecurityGroup => NovaSecurityGroup, SecurityGroupRule}
import com.amazonaws.services.ec2.model.{SecurityGroup => AWSSecurityGroup, IpPermission}
import scala.collection.JavaConversions._
import controllers.{Prism, routes}
import org.jclouds.openstack.nova.v2_0.NovaApi
import agent._
import com.amazonaws.services.ec2.AmazonEC2Client

object SecurityGroupCollectorSet extends CollectorSet[SecurityGroup](ResourceType("security-group", Duration.standardMinutes(15L))) {
  def lookupCollector: PartialFunction[Origin, Collector[SecurityGroup]] = {
    case aws:AmazonOrigin => AWSSecurityGroupCollector(aws, resource)
    case os:OpenstackOrigin => OSSecurityGroupCollector(os, resource)
  }
}

case class AWSSecurityGroupCollector(origin:AmazonOrigin, resource:ResourceType)
    extends Collector[SecurityGroup] {

  def fromAWS( secGroup: AWSSecurityGroup, lookup:Map[String,SecurityGroup]): SecurityGroup = {
    def groupRefs(rule: IpPermission): Seq[SecurityGroupRef] = {
      rule.getUserIdGroupPairs.map { pair =>
        SecurityGroupRef(pair.getGroupId, pair.getUserId, lookup.get(pair.getGroupId).map(_.id))
      }
    }

    val rules = secGroup.getIpPermissions.map { rule =>
      Rule(
        rule.getIpProtocol,
        rule.getFromPort,
        rule.getToPort,
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
      secGroup.getTags.map(t => t.getKey -> t.getValue).toMap
    )
  }

  val client = new AmazonEC2Client(origin.creds)
  client.setEndpoint(s"ec2.${origin.region}.amazonaws.com")

  def crawl: Iterable[SecurityGroup] = {
    // get all existing groups to allow for cross referencing
    val existingGroups = Prism.securityGroupAgent.get().flatMap(_.data).map(sg => sg.groupId -> sg).toMap
    val secGroups = client.describeSecurityGroups.getSecurityGroups
    secGroups.map ( fromAWS(_, existingGroups) )
  }
}

case class OSSecurityGroupCollector(origin:OpenstackOrigin, resource:ResourceType)
    extends Collector[SecurityGroup] {
  lazy val novaApi = ContextBuilder.newBuilder("openstack-nova")
    .endpoint(origin.endpoint)
    .credentials(s"${origin.tenant}:${origin.user}", origin.secret)
    .buildApi(classOf[NovaApi])

  def fromJCloudNova(secGroup: NovaSecurityGroup, lookup:Map[(String,String),SecurityGroup]): SecurityGroup = {

    def groupRefs(rule: SecurityGroupRule) = {
      val groupOption = Option(rule.getGroup)
      groupOption.map { group =>
        SecurityGroupRef(group.getName, group.getTenantId, lookup.get((group.getTenantId,group.getName)).map(_.id))
      }.toSeq
    }

    val rules = Option(secGroup.getRules).map(_.toSeq).getOrElse(Nil).map { rule =>
      Rule(
        rule.getIpProtocol.toString,
        rule.getFromPort,
        rule.getToPort,
        Option(rule.getIpRange).toSeq.wrap,
        groupRefs(rule).wrap
      )
    }
    SecurityGroup(
      s"arn:${origin.vendor}:ec2:${origin.region}:${origin.tenant}:security-group/${secGroup.getName}",
      secGroup.getId,
      secGroup.getName,
      origin.region,
      rules.toSeq,
      Map.empty
    )
  }

  lazy val securityGroupExtension = novaApi.getSecurityGroupExtensionForZone(origin.region).get()

  def crawl: Iterable[SecurityGroup] = {
    val existingGroups = Prism.securityGroupAgent.get().flatMap(_.data).map(sg => (sg.location,sg.name) -> sg).toMap
    val secGroups = securityGroupExtension.list()
    secGroups.map ( fromJCloudNova(_, existingGroups) )
  }
}

case class Rule( protocol:String,
                 fromPort:Int,
                 toPort:Int,
                 sourceCidrBlocks:Option[Seq[String]],
                 sourceGroupRefs:Option[Seq[SecurityGroupRef]] )

case class SecurityGroup(id:String,
                         groupId:String,
                         name:String,
                         location:String,
                         rules:Seq[Rule],
                         tags:Map[String, String]) extends IndexedItem {
  def callFromId: (String) => Call = id => routes.Api.securityGroup(id)
}

case class SecurityGroupRef(groupId:String, account:String, id:Option[String])