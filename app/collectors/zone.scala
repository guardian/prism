package collectors

import agent._
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder
import com.amazonaws.services.route53.model.{HostedZone, ListHostedZonesRequest, ListResourceRecordSetsRequest, ResourceRecordSet}
import controllers.routes
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

object Route53ZoneCollectorSet extends CollectorSet[Route53Zone](ResourceType("route53Zones", 1 hour, 5 minutes)) {
  val lookupCollector: PartialFunction[Origin, Collector[Route53Zone]] = {
    case amazon: AmazonOrigin => Route53ZoneCollector(amazon, resource)
  }
}

case class Route53ZoneCollector(origin: AmazonOrigin, resource: ResourceType) extends Collector[Route53Zone] with Logging {

  val client = AmazonRoute53ClientBuilder.standard()
    .withCredentials(origin.credentials.provider)
    .withRegion(origin.awsRegion)
    .build()

  def crawl: Iterable[Route53Zone] = PaginatedAWSRequest.run(client.listHostedZones)(new ListHostedZonesRequest()).map{ zone =>
    val records = PaginatedAWSRequest.run(client.listResourceRecordSets)(new ListResourceRecordSetsRequest(zone.getId))
    Route53Zone.fromApiData(zone, records, origin)
  }
}

object Route53Zone {
  def fromApiData(zone: HostedZone, awsRecordSets: Iterable[ResourceRecordSet], origin: AmazonOrigin) = {
    val recordSets: List[Route53Record] = awsRecordSets.map { record =>
      val alias = Option(record.getAliasTarget).map(at => Route53Alias(at.getDNSName, at.getHostedZoneId, at.getEvaluateTargetHealth))
      val maybeTtl: Option[Long] = Option(record.getTTL).map(_.longValue)
      val records = record.getResourceRecords.asScala.map(_.getValue).toList
      Route53Record(
        record.getName,
        if (alias.nonEmpty) "ALIAS" else record.getType,
        maybeTtl,
        if (records.isEmpty) None else Some(records),
        alias
      )
    }.toList
    val nameServers = recordSets.filter(rs => rs.name == zone.getName && rs.recordType == "NS").flatMap(_.records.getOrElse(Nil))
    Route53Zone(
      arn = s"arn:aws:route53:::hostedzone/${zone.getId}",
      name = zone.getName,
      id = zone.getId,
      callerReference = zone.getCallerReference,
      comment = Option(zone.getConfig.getComment),
      isPrivateZone = zone.getConfig.getPrivateZone,
      resourceRecordSetCount = zone.getResourceRecordSetCount,
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
  def callFromArn: (String) => Call = arn => routes.Application.index() //routes.Api.route53Zone(arn)
}