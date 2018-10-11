package collectors

import agent._
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder
import com.amazonaws.services.route53.model.{HostedZone, ListHostedZonesRequest}
import controllers.routes
import org.joda.time.Duration
import play.api.mvc.Call
import utils.{Logging, PaginatedAWSRequest}

object Route53ZoneCollectorSet extends CollectorSet[Route53Zone](ResourceType("route53Zones", Duration.standardMinutes(15L))) {
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
    Route53Zone.fromApiData(zone, origin)
  }
}

object Route53Zone {
  def fromApiData(zone: HostedZone, origin: AmazonOrigin) = Route53Zone(
    arn = s"arn:aws:route53:::hostedzone/${zone.getId}",
    name = zone.getName,
    id = zone.getId,
    callerReference = zone.getCallerReference,
    comment = Option(zone.getConfig.getComment),
    isPrivateZone = zone.getConfig.getPrivateZone,
    resourceRecordSetCount = zone.getResourceRecordSetCount
  )
}

case class Route53Zone(
                           arn: String,
                           name: String,
                           id: String,
                           callerReference: String,
                           comment: Option[String],
                           isPrivateZone: Boolean,
                           resourceRecordSetCount: Long
                         ) extends IndexedItem {
  def callFromArn: (String) => Call = arn => routes.Api.route53Zone(arn)
}