package collectors

import org.joda.time.Duration
import play.api.libs.json.Json
import play.api.mvc.Call
import controllers.routes
import agent._

import scala.concurrent.duration._
import scala.language.postfixOps


case class JsonDataCollector(origin:JsonOrigin, resource: ResourceType) extends JsonCollector[Data] {
  implicit val valueReads = Json.reads[Value]
  implicit val dataReads = Json.reads[Data]
  def crawl: Iterable[Data] = crawlJson
}

case class Data( key:String, values:Seq[Value]) extends IndexedItem {
  def arn: String = s"arn:gu:data:key/$key"
  def callFromArn: (String) => Call = arn => routes.Api.data(arn)
  def firstMatchingData(stack:String, app:String, stage:String): Option[Value] = {
    values.find { data =>
      data.appRegex.findFirstMatchIn(app).isDefined &&
        data.stageRegex.findFirstMatchIn(stage).isDefined &&
        data.stackRegex.findFirstMatchIn(stack).isDefined
    }
  }
}

object Data {
  implicit val fields = new Fields[Data] {
    override def defaultFields: Seq[String] = Seq("key")
  }
}

case class Value( stack: String,
                  app: String,
                 stage: String,
                 value: String,
                 comment: Option[String] ) {
  lazy val stackRegex = s"^$stack$$".r
  lazy val appRegex = s"^$app$$".r
  lazy val stageRegex = s"^$stage$$".r
}

object DataCollectorSet extends CollectorSet[Data](ResourceType("data", 15 minutes, 1 minute)) {
  def lookupCollector: PartialFunction[Origin, Collector[Data]] = {
    case json:JsonOrigin => JsonDataCollector(json, resource)
  }
}