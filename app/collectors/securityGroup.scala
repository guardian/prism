package collectors

import org.joda.time.Duration
import play.api.mvc.Call
import utils.Logging
import org.jclouds.ContextBuilder
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.compute.domain.{ SecurityGroup => jcSecurityGroup }
import scala.collection.JavaConversions._
import controllers.routes
import java.net.URI

object SecurityGroupCollectorSet extends CollectorSet[SecurityGroup](ResourceType("security-group", Duration.standardMinutes(15L))) {
  def lookupCollector: PartialFunction[Origin, Collector[SecurityGroup]] = {
    case aws:AmazonOrigin => AWSSecurityGroupCollector(aws, resource)
    case os:OpenstackOrigin => OSSecurityGroupCollector(os, resource)
  }
}

case class AWSSecurityGroupCollector(origin:AmazonOrigin, resource:ResourceType) extends Collector[SecurityGroup] with Logging {
  lazy val context = ContextBuilder.newBuilder("aws-ec2")
    .credentials(origin.accessKey, origin.secretKey)
    .build(classOf[ComputeServiceContext])
  lazy val compute = context.getComputeService
  lazy val securityGroupExtension = compute.getSecurityGroupExtension.get

  def crawl: Iterable[SecurityGroup] = {
    val secGroups = securityGroupExtension.listSecurityGroupsInLocation(origin.jCloudLocation)
    secGroups.map ( SecurityGroup.fromJCloud )
  }
}

case class OSSecurityGroupCollector(origin:OpenstackOrigin, resource:ResourceType) extends Collector[SecurityGroup] {
  lazy val context = ContextBuilder.newBuilder("openstack-nova")
    .endpoint(origin.endpoint)
    .credentials(s"${origin.tenant}:${origin.user}", origin.secret)
    .build(classOf[ComputeServiceContext])
  lazy val compute = context.getComputeService
  lazy val securityGroupExtension = compute.getSecurityGroupExtension.get

  def crawl: Iterable[SecurityGroup] = {
    val secGroups = securityGroupExtension.listSecurityGroups
    secGroups.map ( SecurityGroup.fromJCloud )
  }
}

object SecurityGroup {
  def fromJCloud(secGroup: jcSecurityGroup): SecurityGroup = {
    val rules = secGroup.getIpPermissions.map { rule =>
      Rule(
        rule.getIpProtocol.toString,
        rule.getFromPort,
        rule.getToPort,
        rule.getGroupIds.toSeq.sorted,
        rule.getCidrBlocks.toSeq.sorted,
        rule.getTenantIdGroupNamePairs.asMap.toMap.mapValues(_.toSeq)
      )
    }
    secGroup.getName
    SecurityGroup(
      s"arn:aws:ec2:${secGroup.getLocation.getId}:${secGroup.getOwnerId}:security-group/${secGroup.getProviderId}",
      secGroup.getProviderId,
      secGroup.getName,
      secGroup.getLocation.getId,
      rules.toSeq,
      secGroup.getTags.toSeq,
      Option(secGroup.getUri).map(_.toString),
      secGroup.getUserMetadata.toMap
    )
  }
}

case class Rule( protocol:String,
                 fromPort:Int,
                 toPort:Int,
                 groupIds:Seq[String],
                 sourceCidrBlocks:Seq[String],
                 sourceGroups:Map[String,Seq[String]])

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