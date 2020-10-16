package jsonimplicits

import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json._
import collectors._
import play.api.libs.json.JsString
import agent._
import play.api.mvc.RequestHeader
import _root_.model.{Owner, SSA}
import org.joda.time.DateTime

trait RequestWrites[T] {
  def writes(request: RequestHeader): Writes[T]
}
object RequestWrites {
  def fromWrites[T](implicit delegate:Writes[T]): RequestWrites[T] = (_: RequestHeader) => delegate
}

object joda {
  implicit object dateTimeWrites extends Writes[org.joda.time.DateTime] {
    def writes(d: org.joda.time.DateTime): JsValue = JsString(ISODateTimeFormat.dateTime.print(d))
  }
  implicit val dateTimeReads: Reads[DateTime] = JodaReads.jodaDateReads("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
}

object model {
  import joda.dateTimeWrites

  implicit val originWriter: Writes[Origin] = (o: Origin) => o.toJson

  implicit val securityGroupRefWriter: OWrites[SecurityGroupRef] = Json.writes[SecurityGroupRef]
  implicit val securityGroupRuleWriter: OWrites[Rule] = Json.writes[Rule]
  implicit val securityGroupWriter: OWrites[SecurityGroup] = Json.writes[SecurityGroup]

  implicit def instanceRequestWriter(implicit refWriter: Writes[Reference[SecurityGroup]]): Writes[Instance] = {
    implicit val addressWriter: OWrites[Address] = Json.writes[Address]
    implicit val instanceSpecificationWriter: OWrites[InstanceSpecification] = Json.writes[InstanceSpecification]
    implicit val managementEndpointWriter: OWrites[ManagementEndpoint] = Json.writes[ManagementEndpoint]

    Json.writes[Instance]
  }
  implicit val valueWriter: OWrites[Value] = Json.writes[Value]
  implicit val dataWriter: OWrites[Data] = Json.writes[Data]

  implicit val imageWriter: OWrites[Image] = Json.writes[Image]
  implicit val launchConfigurationWriter: OWrites[LaunchConfiguration] = Json.writes[LaunchConfiguration]
  implicit val serverCertificateWriter: OWrites[ServerCertificate] = Json.writes[ServerCertificate]
  implicit val bucketWriter: OWrites[Bucket] = Json.writes[Bucket]
  implicit val lambdaWriter: OWrites[Lambda] = Json.writes[Lambda]
  implicit val reservationWriter: Writes[Reservation] = {
    implicit val recurringCharge: OWrites[RecurringCharge] = Json.writes[RecurringCharge]
    Json.writes[Reservation]
  }

  implicit val domainResourceRecordWriter: OWrites[DomainResourceRecord] = Json.writes[DomainResourceRecord]
  implicit val domainValidationWriter: OWrites[DomainValidation] = Json.writes[DomainValidation]
  implicit val renewalInfoWriter: OWrites[RenewalInfo] = Json.writes[RenewalInfo]
  implicit val acmCertificateWriter: OWrites[AcmCertificate] = Json.writes[AcmCertificate]
  implicit val route53AliasWriter: OWrites[Route53Alias] = Json.writes[Route53Alias]
  implicit val route53RecordWriter: OWrites[Route53Record] = Json.writes[Route53Record]
  implicit val route53ZoneWriter: OWrites[Route53Zone] = Json.writes[Route53Zone]

  implicit val loadBalancerWriter: OWrites[LoadBalancer] = Json.writes[LoadBalancer]

  implicit val labelWriter:Writes[Label] = (l: Label) => {
    Json.obj(
      "resource" -> l.resource.name,
      "origin" -> l.origin
    ) ++ basicLabelWriter.writes(l).as[JsObject]
  }

  val basicLabelWriter:Writes[Label] = (l: Label) => {
    Json.obj(
      "status" -> l.status,
      "createdAt" -> l.createdAt,
      "itemCount" -> l.itemCount,
      "age" -> l.bestBefore.age.getStandardSeconds,
      "stale" -> l.bestBefore.isStale
    ) ++
      l.error.map { error =>
        Json.obj(
          "message" -> s"${error.getClass.getName}: ${error.getMessage}",
          "stacktrace" -> error.getStackTrace.map(_.toString).take(5)
        )
      }.getOrElse(Json.obj())
  }

  implicit val sourceStatusWriter: Writes[SourceStatus] = new Writes[SourceStatus] {
    implicit val labelWriter: Writes[Label] = basicLabelWriter
    implicit val writes: OWrites[SourceStatus] = Json.writes[SourceStatus]
    def writes(o: SourceStatus): JsValue = {
      Json.obj(
        "resource" -> o.state.resource.name,
        "origin" -> o.state.origin,
        "status" -> o.latest.status
      ) ++ Json.toJson(o).as[JsObject]
    }
  }

  implicit val ssaWriter: Writes[SSA] = (ssa: SSA) => {
    JsObject(
      Seq("stack" -> JsString(ssa.stack)) ++
        ssa.stage.map(stage => "stage" -> JsString(stage)) ++
        ssa.app.map(app => "app" -> JsString(app))
    )
  }

  implicit val ownerWriter: Writes[Owner] = (o: Owner) => {
    Json.obj(
      "id" -> o.id,
      "stacks" -> Json.toJson(o.ssas)
    )
  }
}
