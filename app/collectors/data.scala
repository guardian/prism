package collectors

import org.joda.time.Duration
import play.api.libs.json.Json
import play.api.mvc.Call
import controllers.routes

object DataCollectorSet extends CollectorSet[Data](ResourceType("data", Duration.standardMinutes(15L))) {
  def lookupCollector: PartialFunction[Origin, Collector[Data]] = {
    case json:JsonOrigin => JsonDataCollector(json, resource)
  }
}

case class JsonDataCollector(origin:JsonOrigin, resource: ResourceType) extends JsonCollector[Data] {
  implicit val valueReads = Json.reads[Value]
  implicit val dataReads = Json.reads[Data]
  def crawl: Iterable[Data] = crawlJson
}

case class Data( key:String, values:Seq[Value]) extends IndexedItem {
  def id: String = s"arn:gu:data:key/$key"
  def callFromId: (String) => Call = id => routes.Api.data(id)
  def firstMatchingData(stack:Option[String], app:String, stage:String): Option[Value] = {
    stack.map { s =>
      values.filter {
        _.stack.isDefined
      } find { data =>
        data.appRegex.findFirstMatchIn(app).isDefined &&
          data.stageRegex.findFirstMatchIn(stage).isDefined &&
          data.stackRegex.get.findFirstMatchIn(s).isDefined
      }
    } getOrElse {
      values.filter {
        _.stack.isEmpty
      } find {data =>
        data.appRegex.findFirstMatchIn(app).isDefined && data.stageRegex.findFirstMatchIn(stage).isDefined
      }
    }
  }
}

case class Value( stack: Option[String],
                  app: String,
                 stage: String,
                 value: String,
                 comment: Option[String] ) {
  lazy val stackRegex = stack.map(s => s"^$s$$".r)
  lazy val appRegex = ("^%s$" format app).r
  lazy val stageRegex = ("^%s$" format stage).r
}