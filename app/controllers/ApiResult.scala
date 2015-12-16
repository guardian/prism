package controllers

import play.api.libs.concurrent.Execution.Implicits._
import scala.language.postfixOps
import agent.{Origin, ResourceType, Label}
import model.DataContainer
import org.joda.time.DateTime
import play.api.http.{ContentTypes, Status}
import play.api.libs.json._
import play.api.mvc.{Result, Results, RequestHeader}
import scala.concurrent.Future
import scala.util.Try
import utils.{ResourceFilter, Logging}
import jsonimplicits.joda._

// use this when the API call has illegal parameters
case class ApiCallException(failure:JsObject, status:Int = Status.BAD_REQUEST)
  extends RuntimeException(failure.fields.map(f => s"${f._1}: ${f._2}").mkString("; "))

object ApiResult extends Logging {
  val noSourceContainer = new DataContainer {
    val name = "no data source"
    def lastUpdated: DateTime = new DateTime
    val isStale = false
  }

  def addCountToJson(data: JsValue):JsValue = {
    data match {
      case JsObject(fields) =>
        JsObject(fields.flatMap { case (key, value) =>
          value match {
            case JsArray(array) =>
              List((s"$key.length",JsNumber(array.size)), (key, addCountToJson(value)))
            case _ => List((key, addCountToJson(value)))
          }
        })
      case JsArray(values) =>
        JsArray(values.map(addCountToJson))
      case other => other
    }
  }

  object filter {

    import jsonimplicits.model.labelWriter

    case class SourceData[D](sourceData: Try[Map[Label, Seq[D]]]) {
      def reduce(reduce: Map[Label, Seq[D]] => JsValue)(implicit request: RequestHeader): Future[Result] =
        reduceAsync(input => Future.successful(reduce(input)))(request)

      def reduceAsync(reduce: Map[Label, Seq[D]] => Future[JsValue])(implicit request: RequestHeader): Future[Result] = {
        sourceData.map { mapSources =>
          val filter = ResourceFilter.fromRequest
          val filteredSources = mapSources.groupBy {
            case (label, data) => filter.isMatch(label.origin.filterMap)
          }
          filteredSources.get(false).map(falseMap => if (falseMap.values.exists(_.size == 0)) log.warn(s"The origin filter contract map has been violated: data exists in a discarded source - ${request.uri} from ${request.remoteAddress}"))

          val sources: Map[Label, Seq[D]] = filteredSources.getOrElse(true, Map.empty)

          val usedLabels = sources.filter {
            case (_, data) => !data.isEmpty
          }.keys

          val staleLabels = sources.keys.filter {
            label => label.bestBefore.isStale
          }

          val lastUpdated: DateTime = usedLabels.toSeq.filterNot(_.isError).map(_.createdAt) match {
            case dates: Seq[DateTime] if !dates.isEmpty => dates.min(new Ordering[DateTime] {
              def compare(x: DateTime, y: DateTime): Int = x.getMillis.compareTo(y.getMillis)
            })
            case _ => new DateTime(0)
          }

          val stale = sources.keys.exists(_.bestBefore.isStale)

          reduce(sources).map {
            data =>
              val dataWithMods = if (request.getQueryString("_length").isDefined) addCountToJson(data) else data
              val json = Json.obj(
                "status" -> "success",
                "lastUpdated" -> lastUpdated,
                "stale" -> stale,
                "staleSources" -> staleLabels,
                "data" -> dataWithMods,
                "sources" -> usedLabels
              )
              request.getQueryString("_pretty") match {
                case Some(_) => Results.Ok(Json.prettyPrint(json)).as(ContentTypes.JSON)
                case None => Results.Ok(json)
              }
          }
        } recover {
          case ApiCallException(failure, status) =>
            Future.successful(Results.Status(status)(Json.obj(
              "status" -> "fail",
              "data" -> failure
            )))
          case e: Exception =>
            Future.successful(Results.InternalServerError(Json.obj(
              "status" -> "error",
              "message" -> e.getMessage,
              "stacktrace" -> e.getStackTrace.map(_.toString)
            )))
        } get
      }
    }

    def apply[D](mapSources: => Map[Label, Seq[D]]): SourceData[D] = {
      new SourceData[D](
        Try {
          mapSources
        }
      )
    }
  }

  def noSource(block: => JsValue)(implicit request:RequestHeader): Future[Result] = {
    val sourceLabel:Label = Label(
      ResourceType(noSourceContainer.name, org.joda.time.Duration.standardMinutes(15)),
      new Origin {
        val account = "unknown"
        val vendor = "unknown"
        val resources = Set.empty[String]
        val jsonFields = Map.empty[String, String]
      },
      1,
      noSourceContainer.lastUpdated
    )
    filter(Map(sourceLabel -> Seq("dummy"))) reduceAsync { emptyMap =>
      Future.successful(block)
    }
  }
}
