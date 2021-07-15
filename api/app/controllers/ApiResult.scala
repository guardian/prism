package controllers

import scala.language.postfixOps
import agent.{ApiLabel, ApiOrigin, CrawlRate, Label, Origin, ResourceType}
import model.DataContainer
import org.joda.time.DateTime
import play.api.http.{ContentTypes, Status}
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import utils.{Logging, ResourceFilter}
import jsonimplicits.joda._

import scala.concurrent.duration._
import scala.language.postfixOps

// use this when the API call has illegal parameters
case class ApiCallException(failure:JsObject, status:Int = Status.BAD_REQUEST)
  extends RuntimeException(failure.fields.map(f => s"${f._1}: ${f._2}").mkString("; "))

object ApiResult extends Logging {
  val noSourceContainer: DataContainer = new DataContainer {
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

    case class SourceData[D](sourceData: Try[Map[ApiLabel, Seq[D]]]) {
      def reduce(reduce: Map[ApiLabel, Seq[D]] => JsValue)(implicit request: RequestHeader, ec: ExecutionContext): Future[Result] = {
        reduceAsync(input => Future.successful(reduce(input)))
      }

      def reduceAsync(reduce: Map[ApiLabel, Seq[D]] => Future[JsValue])(implicit request: RequestHeader, ec: ExecutionContext): Future[Result] = {
        val now = new DateTime()
        sourceData.map { mapSources =>
          val filter = ResourceFilter.fromRequest
          val filteredSources = mapSources.groupBy {
            case (label, _) => filter.isMatch(label.origin.filterMap)
          }
          filteredSources.get(false).foreach(falseMap => if (falseMap.values.exists(_.isEmpty)) log.warn(s"The origin filter contract map has been violated: data exists in a discarded source - ${request.uri} from ${request.remoteAddress}"))

          val sources: Map[ApiLabel, Seq[D]] = filteredSources.getOrElse(true, Map.empty)

          val usedLabels = sources.filter {
            case (_, data) => data.nonEmpty
          }.keys

          val staleLabels = sources.keys.filter {
            label => ApiLabel.isStale(label, now)
          }

          val lastUpdated: DateTime = usedLabels.toSeq.filterNot(ApiLabel.isError).map(_.createdAt) match {
            case dates: Seq[DateTime] if dates.nonEmpty => dates.min((x: DateTime, y: DateTime) => {
              x.getMillis.compareTo(y.getMillis)
            })
            case _ => new DateTime(0)
          }

          val stale = sources.keys.exists(ApiLabel.isStale(_, now))

          val reduceSources = reduce(sources).map {
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
          }(ec)
          reduceSources
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

    def apply[D](mapSources: => Map[ApiLabel, Seq[D]]): SourceData[D] = {
      new SourceData[D](
        Try {
          mapSources
        }
      )
    }
  }

  def noSource(block: => JsValue)(implicit request:RequestHeader, ec: ExecutionContext): Future[Result] = {
    val sourceLabel:ApiLabel = ApiLabel(
      noSourceContainer.name,
      ApiOrigin(
        vendor = "unknown",
        accountName = "unknown",
        Map.empty,
        JsObject.empty
      ),
      1,
      noSourceContainer.lastUpdated,
      false,
      None,
      Label.SUCCESS,
      None,
      Nil
    )
    filter(Map(sourceLabel -> Seq("dummy"))) reduceAsync { _ =>
      Future.successful(block)
    }
  }
}
