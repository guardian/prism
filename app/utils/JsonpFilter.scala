package utils

import akka.util.ByteString
import akka.stream.scaladsl.Source
import org.reactivestreams.Publisher
import scala.concurrent.ExecutionContext
import play.api.http.HttpEntity
import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.http.ContentTypes.{JSON, JAVASCRIPT}
import play.api.libs.iteratee.Enumerator
import play.api.libs.streams.Streams
import play.api.mvc._
import play.api.http.Status

/**
 * Transforms JSON responses into JavaScript responses if there is a `paramName` parameter in the request’s query string.
 * This code updated for Play 2.2 from https://github.com/julienrf/play-jsonp-filter
 *
 * See [[http://www.json-p.org/]] for more information about JSONP.
 *
 * @param paramName Name of the query string parameter containing the callback name.
 * @param codec Codec used to serialize the response body
 * @param ex Execution context to use in case of asynchronous results
 */
class JsonpFilter(paramName: String = "callback")(implicit codec: Codec, ex: ExecutionContext) extends EssentialFilter {

  def apply(next: EssentialAction) = EssentialAction { request =>
    request.getQueryString(paramName) match {
      case Some(callback) => next(request).map(jsonpify(callback))
      case None => next(request)
    }
  }

  def jsonpify(callback: String)(result: Result): Result = {
    result.header.headers.get(CONTENT_TYPE) match {
      case Some(ct) if ct == JSON =>
        
        val bodySrc = Source.single(codec.encode(s"$callback(")) concat result.body.dataStream concat Source.single(codec.encode(");"))
        //val bodyPublisher: Publisher[ByteString] = Streams.enumeratorToPublisher(bodyEnumerator)
        //val bodySource: Source[ByteString, _] = Source.fromPublisher(bodyPublisher)
        val entity: HttpEntity = HttpEntity.Streamed(bodySrc, None, None)

        Result(
          header = result.header.copy(status = Status.OK),
          body = entity
        ).as(JAVASCRIPT)
      case _ => result
    }
  }

}