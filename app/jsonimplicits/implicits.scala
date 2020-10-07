package jsonimplicits

import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json._
import collectors._
import play.api.libs.json.JsString
import agent._
import play.api.mvc.RequestHeader
import _root_.model.{Owner, SSA}

trait RequestWrites[T] {
  def writes(request: RequestHeader): Writes[T]
}
object RequestWrites {
  def fromWrites[T](implicit delegate:Writes[T]) = new RequestWrites[T] {
    def writes(request: RequestHeader): Writes[T] = delegate
  }
}

object joda {
  implicit object dateTimeWrites extends Writes[org.joda.time.DateTime] {
    def writes(d: org.joda.time.DateTime): JsValue = JsString(ISODateTimeFormat.dateTime.print(d))
  }
  implicit val dateTimeReads = JodaReads.jodaDateReads("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
}

object model {
  import joda.dateTimeWrites

  implicit val originWriter = new Writes[Origin] {
    def writes(o: Origin): JsValue = o.toJson
  }

  implicit val securityGroupRefWriter = Json.writes[SecurityGroupRef]
  implicit val securityGroupRuleWriter = Json.writes[Rule]
  implicit val securityGroupWriter = Json.writes[SecurityGroup]

  implicit def instanceRequestWriter(implicit refWriter: Writes[Reference[SecurityGroup]]): Writes[Instance] = {
    implicit val addressWriter = Json.writes[Address]
    implicit val instanceSpecificationWriter = Json.writes[InstanceSpecification]
    implicit val managementEndpointWriter = Json.writes[ManagementEndpoint]

    Json.writes[Instance]
  }
  implicit val valueWriter = Json.writes[Value]
  implicit val dataWriter = Json.writes[Data]

  implicit val imageWriter = Json.writes[Image]
  implicit val launchConfigurationWriter = Json.writes[LaunchConfiguration]
  implicit val serverCertificateWriter = Json.writes[ServerCertificate]
  implicit val bucketWriter = Json.writes[Bucket]
  implicit val lambdaWriter = Json.writes[Lambda]
  implicit val reservationWriter: Writes[Reservation] = {
    implicit val recurringCharge = Json.writes[RecurringCharge]
    Json.writes[Reservation]
  }

  implicit val domainResourceRecordWriter = Json.writes[DomainResourceRecord]
  implicit val domainValidationWriter = Json.writes[DomainValidation]
  implicit val renewalInfoWriter = Json.writes[RenewalInfo]
  implicit val acmCertificateWriter = Json.writes[AcmCertificate]
  implicit val route53AliasWriter = Json.writes[Route53Alias]
  implicit val route53RecordWriter = Json.writes[Route53Record]
  implicit val route53ZoneWriter = Json.writes[Route53Zone]

  implicit val loadBalancerWriter = Json.writes[LoadBalancer]

  implicit val labelWriter:Writes[Label] = new Writes[Label] {
    def writes(l: Label): JsValue = {
      Json.obj(
        "resource" -> l.resource.name,
        "origin" -> l.origin
      ) ++ basicLabelWriter.writes(l).as[JsObject]
    }
  }

  val basicLabelWriter:Writes[Label] = new Writes[Label] {
    def writes(l: Label): JsValue = {
      Json.obj(
        "status" -> l.status,
        "createdAt" -> l.createdAt,
        "itemCount" -> l.itemCount,
        "age" -> l.bestBefore.age.getStandardSeconds,
        "stale" -> l.bestBefore.isStale
      ) ++
        l.error.map{ error => Json.obj(
          "message" -> s"${error.getClass.getName}: ${error.getMessage}",
          "stacktrace" -> error.getStackTrace.map(_.toString).take(5)
        )
        }.getOrElse(Json.obj())
    }
  }

//  implicit val sourceStatusWriter = new Writes[SourceStatus] {
//    implicit val labelWriter = basicLabelWriter
//    implicit val writes = Json.writes[SourceStatus]
//    def writes(o: SourceStatus): JsValue = {
//      Json.obj(
//        "resource" -> o.state.resource.name,
//        "origin" -> o.state.origin,
//        "status" -> o.latest.status
//      ) ++ Json.toJson(o).as[JsObject]
//    }
//  }

  implicit def referenceReads[T](implicit idLookup:ArnLookup[T]): Reads[Reference[T]] = new Reads[Reference[T]] {
    override def reads(json: JsValue): JsResult[Reference[T]] = JsSuccess(Reference[T](json.as[String]))
  }

  implicit val ssaWriter = new Writes[SSA] {
    def writes(ssa: SSA): JsValue = {
      JsObject(
        Seq("stack" -> JsString(ssa.stack)) ++
        ssa.stage.map(stage => "stage" -> JsString(stage)) ++
        ssa.app.map(app => "app" -> JsString(app))
      )
    }
  }

  implicit val ownerWriter = new Writes[Owner] {
    def writes(o: Owner): JsValue = {
      Json.obj(
        "id" -> o.id,
        "stacks" -> Json.toJson(o.ssas)
      )
    }
  }
}
