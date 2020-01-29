package agent

import controllers.Prism
import org.joda.time.DateTime
import utils.{CaseClassFieldList, Logging}

import scala.concurrent.duration._
import scala.language.postfixOps

case class Metadata(key: String, description: String, defaultFields: Seq[String], allFields: Seq[String])

object Metadata extends Logging {
  val initTime = new DateTime()

  def prependMetaOrigin(fields:List[String]): List[String] = fields.map(f => s"meta.origin.$f")

  // this should really be automatically generated from all class names but
  // that's a bit tricky without further reflection and life is too short
  val originFieldsMap: Map[String, List[String]] = Map(
    classOf[AmazonOrigin].getName -> CaseClassFieldList.allFields[AmazonOrigin],
    classOf[JsonOrigin].getName -> CaseClassFieldList.allFields[JsonOrigin],
    classOf[GoogleDocOrigin].getName -> CaseClassFieldList.allFields[GoogleDocOrigin]
  ).mapValues(prependMetaOrigin)

  def metadata:Datum[Metadata] = {
    val typeList = Prism.allAgents.map { agent =>
      val key = agent.collectorSet.resource.name
      //val desc = agent.collectorSet.resource.description
      val defFields = agent.collectorSet.defaultFields
      val typeFields = agent.collectorSet.allFields
      val originTypes = agent.collectorSet.collectors.map(_.origin.getClass.getName).distinct
      val originFields = originTypes.flatMap(originFieldsMap.getOrElse(_, Nil))
      val allFields = typeFields ++ originFields
      Metadata(key, "", defFields, allFields)
    }
    val label = Label(
      ResourceType("metadata", 1 day, 1 day),
      new Origin {
        val vendor = "prism"
        val account = "prism"
        val resources = Set("metadata")
        val jsonFields = Map.empty[String, String]
      },
      typeList.size,
      initTime
    )
    Datum(label, typeList)
  }
}
