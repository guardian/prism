package jsonimplicits

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json.{Format, JodaReads, JsString, JsValue, Reads, Writes}

object Joda {
  private object dateTimeWrites extends Writes[org.joda.time.DateTime] {
    def writes(d: org.joda.time.DateTime): JsValue = JsString(ISODateTimeFormat.dateTime.print(d))
  }
  private val dateTimeReads: Reads[DateTime] = JodaReads.jodaDateReads("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
  implicit val dateTimeFormats: Format[DateTime] = Format(dateTimeReads, dateTimeWrites)
}
