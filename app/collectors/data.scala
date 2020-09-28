package collectors

import play.api.libs.json.{Json, Reads}
import play.api.mvc.Call
import controllers.routes
import agent._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.matching.Regex

class DataCollectorSet(accounts: Accounts) extends CollectorSet[Data](ResourceType("data", 15 minutes, 1 minute), accounts) {
  def lookupCollector: PartialFunction[Origin, Collector[Data]] = {
    case json:JsonOrigin => JsonDataCollector(json, resource)
  }
}

case class JsonDataCollector(origin:JsonOrigin, resource: ResourceType) extends JsonCollector[Data] {
  implicit val valueReads: Reads[Value] = Json.reads[Value]
  implicit val dataReads: Reads[Data] = Json.reads[Data]
  def crawl: Iterable[Data] = crawlJson
}

case class Data( key:String, values:Seq[Value]) extends IndexedItem {
  def arn: String = s"arn:gu:data:key/$key"
  def callFromArn: String => Call = arn => routes.Api.data(arn)
  def firstMatchingData(stack:String, app:String, stage:String): Option[Value] = {
    values.find { data =>
      data.appRegex.findFirstMatchIn(app).isDefined &&
        data.stageRegex.findFirstMatchIn(stage).isDefined &&
        data.stackRegex.findFirstMatchIn(stack).isDefined
    }
  }
}

case class Value( stack: String,
                  app: String,
                 stage: String,
                 value: String,
                 comment: Option[String] ) {
  lazy val stackRegex: Regex = s"^$stack$$".r
  lazy val appRegex: Regex = s"^$app$$".r
  lazy val stageRegex: Regex = s"^$stage$$".r
}