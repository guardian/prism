package collectors

import org.joda.time.Duration
import play.api.mvc.Call
import org.jclouds.ContextBuilder
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.compute.domain.{ SecurityGroup => JCSecurityGroup }
import org.jclouds.openstack.nova.v2_0.domain.{SecurityGroup => NovaSecurityGroup, SecurityGroupRule}
import scala.collection.JavaConversions._
import controllers.{Prism, routes}
import org.jclouds.net.domain.IpPermission
import org.jclouds.openstack.nova.v2_0.NovaApi
import agent._

object SecurityGroupCollectorSet extends CollectorSet[SecurityGroup](ResourceType("security-group", Duration.standardMinutes(15L))) {
  def lookupCollector: PartialFunction[Origin, Collector[SecurityGroup]] = {
    case aws:AmazonOrigin => AWSSecurityGroupCollector(aws, resource)
    case os:OpenstackOrigin => OSSecurityGroupCollector(os, resource)
  }
}

case class AWSSecurityGroupCollector(origin:AmazonOrigin, resource:ResourceType)
    extends Collector[SecurityGroup] {
  val originVendor = origin.vendor
  def originAccount(group:JCSecurityGroup) = group.getOwnerId

  def fromJCloud(secGroup: JCSecurityGroup, lookup:Map[String,SecurityGroup]): SecurityGroup = {

    def groupRefs(rule: IpPermission): Seq[SecurityGroupRef] = {
      val tenantIdGroupNamePairs = rule.getTenantIdGroupNamePairs.asMap.toMap.mapValues(_.toSeq)
      val tenantIdSGRefs = tenantIdGroupNamePairs.flatMap { case (account, groups) =>
        groups.map { group =>
          SecurityGroupRef(group, Some(account), lookup.get(group).map(_.id))
        }
      }.toSeq
      val groupIds = rule.getGroupIds.toSeq
      tenantIdSGRefs ++ groupIds.map { group =>
        SecurityGroupRef(group, None, lookup.get(group).map(_.id))
      }
    }

    val rules = secGroup.getIpPermissions.map { rule =>
      Rule(
        rule.getIpProtocol.toString,
        rule.getFromPort,
        rule.getToPort,
        rule.getCidrBlocks.toSeq.sorted.wrap,
        groupRefs(rule).wrap
      )
    }
    secGroup.getName
    SecurityGroup(
      s"arn:$originVendor:ec2:${secGroup.getLocation.getId}:${originAccount(secGroup)}:security-group/${secGroup.getProviderId}",
      secGroup.getProviderId,
      secGroup.getName,
      secGroup.getLocation.getId,
      rules.toSeq,
      secGroup.getTags.toSeq,
      Option(secGroup.getUri).map(_.toString),
      secGroup.getUserMetadata.toMap
    )
  }

  lazy val context = ContextBuilder.newBuilder("aws-ec2")
    .credentials(origin.accessKey, origin.secretKey)
    .build(classOf[ComputeServiceContext])
  lazy val compute = context.getComputeService
  lazy val securityGroupExtension = compute.getSecurityGroupExtension.get

  def crawl: Iterable[SecurityGroup] = {
    // get all existing groups to allow for cross referencing
    val existingGroups = Prism.securityGroupAgent.get().flatMap(_.data).map(sg => sg.groupId -> sg).toMap
    val secGroups = securityGroupExtension.listSecurityGroupsInLocation(origin.jCloudLocation)
    secGroups.map ( fromJCloud(_, existingGroups) )
  }
}

case class OSSecurityGroupCollector(origin:OpenstackOrigin, resource:ResourceType)
    extends Collector[SecurityGroup] {
  val originVendor = origin.vendor
  lazy val novaApi = ContextBuilder.newBuilder("openstack-nova")
    .endpoint(origin.endpoint)
    .credentials(s"${origin.tenant}:${origin.user}", origin.secret)
    .buildApi(classOf[NovaApi])

  def fromJCloudNova(secGroup: NovaSecurityGroup, lookup:Map[(String,String),SecurityGroup]): SecurityGroup = {

    def groupRefs(rule: SecurityGroupRule) = {
      val groupOption = Option(rule.getGroup)
      groupOption.map { group =>
        SecurityGroupRef(group.getName, Some(group.getTenantId), lookup.get((group.getTenantId,group.getName)).map(_.id))
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
      s"arn:$originVendor:ec2:${origin.region}:${origin.tenant}:security-group/${secGroup.getName}",
      secGroup.getId,
      secGroup.getName,
      secGroup.getTenantId,
      rules.toSeq,
      Nil,
      None,
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
                         tags:Seq[String],
                         uri: Option[String],
                         userTags:Map[String,String]) extends IndexedItem {
  def callFromId: (String) => Call = id => routes.Api.securityGroup(id)
}

case class SecurityGroupRef(groupId:String, account:Option[String], id:Option[String])