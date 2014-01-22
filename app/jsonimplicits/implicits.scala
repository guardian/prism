package jsonimplicits

import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json._
import collectors._
import play.api.libs.json.JsString

object joda {
  implicit object dateTimeWrites extends Writes[org.joda.time.DateTime] {
    def writes(d: org.joda.time.DateTime): JsValue = JsString(ISODateTimeFormat.dateTime.print(d))
  }
  implicit val dateTimeReads = Reads.jodaDateReads("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
}

object model {
  import joda.dateTimeWrites

  implicit val instanceWriter = Json.writes[Instance]
  implicit val valueWriter = Json.writes[Value]
  implicit val dataWriter = Json.writes[Data]
  implicit val networkInterfaceWriter = Json.writes[NetworkInterface]
  implicit val logicalInterfaceWriter = Json.writes[LogicalInterface]
  implicit val hardwareWriter = Json.writes[Hardware]
  implicit val securityGroupRefWriter = Json.writes[SecurityGroupRef]
  implicit val securityGroupRuleWriter = Json.writes[Rule]
  implicit val securityGroupWriter = Json.writes[SecurityGroup]

  implicit val originWriter = new Writes[Origin] {
    def writes(o: Origin): JsValue = Json.obj("vendor" -> o.vendor, "account" -> o.account)
  }

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
        "age" -> l.bestBefore.age.getStandardSeconds,
        "stale" -> l.bestBefore.isStale
      ) ++
        l.error.map{ error => Json.obj(
          "message" -> error.getMessage
        )
        }.getOrElse(Json.obj())
    }
  }

  implicit val sourceStatusWriter = new Writes[SourceStatus] {
    implicit val labelWriter = basicLabelWriter
    implicit val writes = Json.writes[SourceStatus]
    def writes(o: SourceStatus): JsValue = {
      Json.obj(
        "resource" -> o.state.resource.name,
        "origin" -> o.state.origin,
        "status" -> o.latest.status
      ) ++ Json.toJson(o).as[JsObject]
    }
  }
}
