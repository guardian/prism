package utils

import scala.util.parsing.combinator._
import java.net.{URLDecoder, URL}
import play.api.libs.ws.WS
import play.api.http.Status._
import play.api.http.HeaderNames
import scala.concurrent.Future
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Play.current

// A CSV parser based on RFC4180
// http://tools.ietf.org/html/rfc4180
// http://stackoverflow.com/questions/5063022/use-scala-parser-combinator-to-parse-csv-files

object CSV extends RegexParsers {
  override val skipWhitespace = false   // meaningful spaces in CSV

  def COMMA   = ","
  def DQUOTE  = "\""
  def DQUOTE2 = "\"\"" ^^ { case _ => "\"" }  // combine 2 dquotes into 1
  def CRLF    = "\r\n" | "\n"
  def TXT     = "[^\",\r\n]".r
  def SPACES  = "[ \t]+".r

  def file: Parser[List[List[String]]] = repsep(record, CRLF) <~ (CRLF?)

  def record: Parser[List[String]] = repsep(field, COMMA)

  def field: Parser[String] = escaped|nonescaped

  def escaped: Parser[String] = {
    ((SPACES?)~>DQUOTE~>((TXT|COMMA|CRLF|DQUOTE2)*)<~DQUOTE<~(SPACES?)) ^^ {
      case ls => ls.mkString("")
    }
  }

  def nonescaped: Parser[String] = (TXT*) ^^ { case ls => ls.mkString("") }

  def parse(s: String) = parseAll(file, s) match {
    case Success(res, _) => res
    case e => throw new Exception(e.toString)
  }
}

object GoogleDoc extends Logging {
  def getCsvForDoc(docUrl:URL): Future[List[List[String]]] = {
    getUrlData(docUrl).map(CSV.parse)
  }

  def getUrlData(url:URL, cookies: Map[String, String] = Map.empty, redirects:Int = 0): Future[String] = {
    assert(redirects < 5, "Too many redirects")
    val redirectStatus = Set(MULTIPLE_CHOICES, MOVED_PERMANENTLY, FOUND, SEE_OTHER, NOT_MODIFIED, USE_PROXY, TEMPORARY_REDIRECT)
    val headers:Seq[(String,String)] = if (cookies.isEmpty) Seq.empty else {
      Seq(HeaderNames.COOKIE -> cookies.map{case (name, value) => s"$name=$value"}.mkString("; "))
    }
    WS.url(url.toString)
      .withFollowRedirects(false)
      .withHeaders(headers:_*)
      .get().flatMap { response =>
      if (redirectStatus.contains(response.status)) {
        // follow redirect
        val redirectURL = response.header(HeaderNames.LOCATION).map { location =>
          //val decodedLocation = URLDecoder.decode(location ,"utf-8")
          val target = new URL(url, location)
          target
        }
        assert(redirectURL.isDefined, s"Bad location redirect from ${url.toString}")
        assert(Set("http","https").contains(redirectURL.get.getProtocol), "Illegal redirect protocol")

        val newCookies = response.cookies.flatMap { cookie =>
          if (cookie.name.isDefined && cookie.value.isDefined) {
            Some(cookie.name.get -> cookie.value.get)
          } else None
        }.toMap

        getUrlData(redirectURL.get, cookies ++ newCookies, redirects+1)
      } else {
        Future.successful(response.body)
      }
    }
  }
}