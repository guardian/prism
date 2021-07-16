package agent

import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._
import jsonimplicits.Joda._

object ApiOrigin {
  private val writes = new OWrites[ApiOrigin] {
    override def writes(o: ApiOrigin): JsObject = Json.obj(
      "vendor" -> o.vendor,
      "accountName" -> o.accountName
    ) ++ o.fields
  }
  private val reads: Reads[ApiOrigin] =
    (
      (JsPath \ "vendor").read[String] and
      (JsPath \ "accountName").read[String] and
      (JsPath \ "filterMap").read[Map[String, String]] and
      (JsPath.read[JsObject].map { obj =>
        obj - "vendor" - "accountName" - "filterMap"
      })
    )(ApiOrigin.apply _)
  implicit val formats: Format[ApiOrigin] = Format(reads, writes)

  def fromOrigin(origin: Origin): ApiOrigin = {
    ApiOrigin(
      origin.vendor,
      origin.account,
      origin.filterMap,
      Json.toJsObject(origin.jsonFields)
    )
  }
}

case class ApiOrigin(vendor: String, accountName: String, filterMap: Map[String, String], fields: JsObject)

object ApiLabel {
  implicit def format: OFormat[ApiLabel] = Json.format[ApiLabel]

  def fromLabel(label: Label): ApiLabel = {
    val shelflife = label.origin.crawlRate(label.resourceType.name).shelfLife
    ApiLabel(
      label.resourceType.name,
      ApiOrigin.fromOrigin(label.origin),
      label.itemCount,
      label.createdAt,
      shelflife.isFinite,
      if (shelflife.isFinite) Some(shelflife.toSeconds) else None,
      label.status,
      label.error.map(e => s"${e.getClass.getName}: ${e.getMessage}"),
      label.error.map(_.getStackTrace.map(_.toString).take(5).toList).getOrElse(Nil)
    )
  }

  def isStale(label: ApiLabel, now: DateTime): Boolean = {
    label.message.nonEmpty ||
      ( label.perishable &&
        label.shelfLifeSeconds.exists(sl => (now.getMillis - label.createdAt.getMillis) >= sl * 1000)
        )
  }

  def isError(label: ApiLabel): Boolean = {
    label.status == Label.ERROR
  }
}

case class ApiLabel(
  resource: String,
  origin: ApiOrigin,
  itemCount: Int,
  createdAt: DateTime,
  perishable: Boolean,
  shelfLifeSeconds: Option[Long],
  status: String,
  message:Option[String],
  stacktrace:List[String]
)

object ApiDatum {
  implicit def format[T](implicit tFormat: Format[T]) = Json.format[ApiDatum[T]]

  def fromDatum[T](datum: Datum[T]): ApiDatum[T] = {
    ApiDatum(ApiLabel.fromLabel(datum.label), datum.data)
  }
}

case class ApiDatum[T](label: ApiLabel, data: Seq[T])
