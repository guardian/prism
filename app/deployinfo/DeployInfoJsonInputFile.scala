package deployinfo

import io.Source
import java.io.File
import org.joda.time.DateTime
import play.api.libs.json._

case class DeployInfoJsonInputFile(
  hosts: List[DeployInfoHost],
  keys: Option[List[DeployInfoKey]],
  data: Map[String,List[DeployInfoData]]
)

case class DeployInfoHost(
  hostname: String,
  app: String,
  arn: String,
  group: String,
  stage: String,
  role: String,
  stack: Option[String],
  apps: Option[List[String]],
  tags: Map[String, String],
  instancename: String,
  internalname: String,
  dnsname: String,
  created_at: String
)

case class DeployInfoKey(
  app: String,
  stage: String,
  accesskey: String,
  comment: Option[String]
)

case class DeployInfoData(
  app: String,
  stage: String,
  value: String,
  comment: Option[String]
)

object DeployInfoJsonReader {
  private implicit val hostReads = Json.reads[DeployInfoHost]
  private implicit val keyReads = Json.reads[DeployInfoKey]
  private implicit val dataReads = Json.reads[DeployInfoData]
  private implicit val fileReads = Json.reads[DeployInfoJsonInputFile]

  def parse(f: File): DeployInfo = parse(Source.fromFile(f).mkString)

  def parse(inputFile: DeployInfoJsonInputFile): DeployInfo = DeployInfo(inputFile, Some(new DateTime()))

  def parse(json: JsValue): DeployInfo = parse(json.as[DeployInfoJsonInputFile])

  def parse(s: String): DeployInfo = parse(Json.parse(s))

}


