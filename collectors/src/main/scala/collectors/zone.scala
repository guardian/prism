package collectors

import agent._
import conf.AwsClientConfig.clientConfig
import software.amazon.awssdk.services.route53.Route53Client
import software.amazon.awssdk.services.route53.model.{HostedZone, ListHostedZonesRequest, ListResourceRecordSetsRequest, ResourceRecordSet}
import utils.Logging

import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class Route53ZoneCollectorSet(accounts: Accounts) extends CollectorSet[Route53Zone](ResourceType("route53Zones"), accounts, Some(Global)) {

  val lookupCollector: PartialFunction[Origin, Collector[Route53Zone]] = {
    case amazon: AmazonOrigin => Route53ZoneCollector(amazon, resource, amazon.crawlRate(resource.name))
  }
}

case class Route53ZoneCollector(origin: AmazonOrigin, resource: ResourceType, crawlRate: CrawlRate) extends Collector[Route53Zone] with Logging {

  val client: Route53Client = Route53Client
    .builder
    .credentialsProvider(origin.credentials.provider)
    .region(origin.awsRegionV2)
    .overrideConfiguration(clientConfig)
    .build

  def crawl: Iterable[Route53Zone] = {
     client.listHostedZonesPaginator(ListHostedZonesRequest.builder.build).hostedZones.asScala.map { zone =>
       val records = client.listResourceRecordSetsPaginator(ListResourceRecordSetsRequest.builder.hostedZoneId(zone.id).build).resourceRecordSets.asScala
       Route53Zone.fromApiData(zone, records, origin)
     }
  }
}

object Route53Zone {
  def fromApiData(zone: HostedZone, awsRecordSets: Iterable[ResourceRecordSet], origin: AmazonOrigin): Route53Zone = {
    val recordSets: List[Route53Record] = awsRecordSets.map { record =>
      val alias = Option(record.aliasTarget).map(at => Route53Alias(at.dnsName, at.hostedZoneId, at.evaluateTargetHealth))
      val maybeTtl: Option[Long] = Option(record.ttl).map(_.longValue)
      val records = record.resourceRecords.asScala.map(_.value).toList
      Route53Record(
        record.name,
        if (alias.nonEmpty) "ALIAS" else record.typeAsString,
        maybeTtl,
        if (records.isEmpty) None else Some(records),
        alias
      )
    }.toList
    val nameServers = recordSets.filter(rs => rs.name == zone.name && rs.recordType == "NS").flatMap(_.records.getOrElse(Nil))
    Route53Zone(
      arn = s"arn:aws:route53:::hostedzone/${zone.id}",
      name = zone.name,
      id = zone.id,
      callerReference = zone.callerReference,
      comment = Option(zone.config.comment),
      isPrivateZone = zone.config.privateZone,
      resourceRecordSetCount = zone.resourceRecordSetCount,
      nameServers = nameServers,
      records = recordSets
    )
  }
}

case class Route53Alias(dnsName: String, hostedZoneId: String, evaluateHealth: Boolean)

case class Route53Record(name: String, recordType: String, ttl: Option[Long], records: Option[List[String]], aliasTarget: Option[Route53Alias])

case class Route53Zone(
                           arn: String,
                           name: String,
                           id: String,
                           callerReference: String,
                           comment: Option[String],
                           isPrivateZone: Boolean,
                           resourceRecordSetCount: Long,
                           nameServers: List[String],
                           records: List[Route53Record]
                         ) extends IndexedItem {
//  def callFromArn: (String) => Call = arn => routes.Api.route53Zone(arn)
}