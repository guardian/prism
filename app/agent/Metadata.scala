package agent

import controllers.Prism
import org.joda.time.DateTime
import scala.concurrent.duration._

import scala.language.postfixOps

case class Metadata(key: String, description: String, defaultFields: Seq[String], allFields: Seq[String])

object Metadata {
  val initTime = new DateTime()

  def metadata:Datum[Metadata] = {
    val typeList = Prism.allAgents.map { agent =>
      val key = agent.collectorSet.resource.name
      //val desc = agent.collectorSet.resource.description
      val defFields = agent.collectorSet.defaultFields
      val allFields = agent.collectorSet.allFields
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
