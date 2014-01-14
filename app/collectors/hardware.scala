package collectors

import play.api.libs.json._
import play.api.libs.functional.syntax._
import org.joda.time.{Duration, DateTime}
import play.api.mvc.Call
import  controllers.routes
import scala.language.postfixOps

object HardwareCollectorSet extends CollectorSet[Hardware](ResourceType("hardware", Duration.standardMinutes(15L))) {
  def lookupCollector: PartialFunction[Origin, Collector[Hardware]] = {
    case json:JsonOrigin => JsonHardwareCollector(json, resource)
  }
}

case class JsonHardwareCollector(origin:JsonOrigin, resource: ResourceType) extends JsonCollectorTranslator[HardwareJson, Hardware] {
  import jsonimplicits.joda.dateTimeReads
  implicit val networkInterfaceReads = Json.reads[NetworkInterface]
  implicit val logicalInterfaceReads = Json.reads[LogicalInterfaceJson]
  implicit val hardwareReads = Json.reads[HardwareJson]

  def crawl: Iterable[Hardware] = crawlJson
  def translate(input: HardwareJson): Hardware = Hardware.fromJson(input)
}

case class HardwareJson(
                         asset: String,
                         hostname: String,
                         domain: String,
                         createdAt: DateTime,
                         vendor: String,
                         region: String,
                         group: Option[String],
                         stage: Option[String],
                         stack: Option[String],
                         apps: Option[List[String]],
                         interfaces: Seq[LogicalInterfaceJson],
                         tags: Map[String,String])

case class LogicalInterfaceJson(
                                 ip: Option[String],
                                 vlan: Option[String],
                                 nics: Seq[NetworkInterface],
                                 management: Option[String]
                                 )



object Hardware {
  def fromJson(i: HardwareJson):Hardware = {
    val id = s"arn:${i.vendor}:hardware:${i.region}:asset/${i.asset}"
    val dnsName = s"${i.hostname}.${i.domain}"
    Hardware(
      id,
      i.asset,
      i.hostname,
      i.domain,
      dnsName,
      i.createdAt,
      i.vendor,
      i.region,
      i.group.getOrElse(i.region),
      i.stage,
      i.stack,
      i.apps.getOrElse(Nil),
      i.interfaces.map(LogicalInterface.fromJson(i, _)),
      i.tags
    )
  }
}

case class Hardware(
    id: String,
    asset: String,
    hostname: String,
    domain: String,
    dnsName: String,
    createdAt: DateTime,
    vendor: String,
    region: String,
    group: String,
    stage: Option[String],
    stack: Option[String],
    apps: List[String],
    interfaces: Seq[LogicalInterface],
    tags: Map[String,String]
) extends IndexedItem {
  def callFromId: (String) => Call = id => routes.Api.hardware(id)
  override lazy val fieldIndex: Map[String, String] = super.fieldIndex ++ Map("dnsName" -> dnsName) ++ stage.map("stage" ->)
}

object LogicalInterface {
  def fromJson(hardware: HardwareJson, input:LogicalInterfaceJson): LogicalInterface = {
    val dnsName = input.ip.flatMap{ _ =>
      (input.vlan, input.management) match {
        case (_, Some(managementType)) => Some(s"${hardware.hostname}-mgt.${hardware.domain}")
        case (Some(vlan), None) => Some(s"${hardware.hostname}-vlan$vlan.${hardware.domain}")
        case _ => None
      }
    }
    LogicalInterface(input.ip, dnsName, input.vlan.map(_.toInt), input.nics, input.management)
  }
}

case class LogicalInterface(
    ip: Option[String],
    dnsName: Option[String],
    vlan: Option[Int],
    nics: Seq[NetworkInterface],
    management: Option[String]
                             )

case class NetworkInterface(
   mac: String,
   dhcp: Option[Boolean]
                             )