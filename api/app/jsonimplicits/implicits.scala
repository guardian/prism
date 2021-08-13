package jsonimplicits

import _root_.model.{Owner, SSA}
import agent._
import ai.x.play.json.Encoders.encoder
import ai.x.play.json.Jsonx
import collectors._
import jsonimplicits.Joda._
import play.api.libs.json.{JsString, _}
import play.api.mvc.RequestHeader
import utils.SourceStatus

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZoneOffset}

trait RequestWrites[T] {
  def writes(request: RequestHeader): Writes[T]
}
object RequestWrites {
  def fromWrites[T](implicit delegate:Writes[T]): RequestWrites[T] = (_: RequestHeader) => delegate
}

object model {
  val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX").withZone(ZoneId.from(ZoneOffset.UTC))
  implicit val instantWrites: Writes[Instant] = Writes.temporalWrites[Instant, DateTimeFormatter](formatter)

  implicit val originWriter: Writes[Origin] = (o: Origin) => o.toJson

  implicit val securityGroupRefWriter: Writes[SecurityGroupRef] = Json.writes[SecurityGroupRef]
  implicit val securityGroupRuleWriter: Writes[Rule] = Json.writes[Rule]
  implicit val securityGroupWriter: Writes[SecurityGroup] = Json.writes[SecurityGroup]

  implicit val instanceRequestWriter: OFormat[Instance] = {
    implicit val addressWriter: OFormat[Address] = Json.format[Address]
    implicit val instanceSpecificationWriter: OFormat[InstanceSpecification] = Json.format[InstanceSpecification]
    implicit val managementEndpointWriter: OFormat[ManagementEndpoint] = Json.format[ManagementEndpoint]

    Json.format[Instance]
  }
  implicit val valueWriter: Writes[Value] = Json.writes[Value]
  implicit val dataWriter: Writes[Data] = Json.writes[Data]

  implicit val imageWriter: Writes[Image] = Json.writes[Image]
  implicit val launchConfigurationWriter: Writes[LaunchConfiguration] = Json.writes[LaunchConfiguration]
  implicit val serverCertificateWriter: Writes[ServerCertificate] = Json.writes[ServerCertificate]
  implicit val bucketWriter: Writes[Bucket] = Json.writes[Bucket]
  implicit val lambdaFormat: OFormat[Lambda] = Json.format[Lambda]
  implicit val reservationWriter: Writes[Reservation] = {
    implicit val recurringCharge: Writes[RecurringCharge] = Json.writes[RecurringCharge]
    Json.writes[Reservation]
  }
  // this case class contains over 22 fields, which is more than Scala permits, so using the play-json-extensions library instead of the normal Json.writes
  implicit val rdsWriter: Writes[Rds] = Jsonx.formatCaseClass[Rds]

  implicit val domainResourceRecordWriter: Writes[DomainResourceRecord] = Json.writes[DomainResourceRecord]
  implicit val domainValidationWriter: Writes[DomainValidation] = Json.writes[DomainValidation]
  implicit val renewalInfoWriter: Writes[RenewalInfo] = Json.writes[RenewalInfo]
  implicit val acmCertificateWriter: Writes[AcmCertificate] = Json.writes[AcmCertificate]
  implicit val route53AliasWriter: Writes[Route53Alias] = Json.writes[Route53Alias]
  implicit val route53RecordWriter: Writes[Route53Record] = Json.writes[Route53Record]
  implicit val route53ZoneWriter: Writes[Route53Zone] = Json.writes[Route53Zone]

  implicit val subnetWriter: Writes[Subnet] = Json.writes[Subnet]
  implicit val vpcWriter: Writes[Vpc] = Json.writes[Vpc]

  implicit val cloudformationStackDriftInformationWriter: Writes[CloudformationStackDriftInformation] = Json.writes[CloudformationStackDriftInformation]
  implicit val cloudformationStackOutput: Writes[CloudformationStackOutput] = Json.writes[CloudformationStackOutput]
  implicit val cloudformationStackParameter: Writes[CloudformationStackParameter] = Json.writes[CloudformationStackParameter]
  implicit val cloudformationStackResource: Writes[CloudformationStackResource] = Json.writes[CloudformationStackResource]
  implicit val cloudformationStackWriter: Writes[CloudformationStack] = Json.writes[CloudformationStack]

  implicit val loadBalancerWriter: Writes[LoadBalancer] = Json.writes[LoadBalancer]

  implicit val awsAccountWrites = Json.writes[AWSAccount]

  implicit val labelWriter:Writes[Label] = (l: Label) => {
    Json.obj(
      "resource" -> l.resourceType.name,
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
        "resource" -> o.state.resourceType.name,
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
