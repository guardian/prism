package utils

import scala.concurrent.ExecutionContext
import play.api.http.HeaderNames.CONTENT_TYPE
import play.api.http.ContentTypes.{JSON, JAVASCRIPT}
import play.api.libs.iteratee.Enumerator
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
        Result(
          header = result.header.copy(status = Status.OK),
          body = Enumerator(codec.encode(s"$callback(")) >>> result.body >>> Enumerator(codec.encode(");")),
          connection = result.connection
        ).as(JAVASCRIPT)
      case _ => result
    }
  }

}