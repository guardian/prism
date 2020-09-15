package utils

import scala.util.parsing.combinator._
import java.net.URL

import play.api.http.Status._
import play.api.http.HeaderNames
import play.api.libs.ws.WSClient

import scala.concurrent.Future
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.matching.Regex


// A CSV parser based on RFC4180
// http://tools.ietf.org/html/rfc4180
// http://stackoverflow.com/questions/5063022/use-scala-parser-combinator-to-parse-csv-files

object CSV extends RegexParsers {
  override val skipWhitespace = false   // meaningful spaces in CSV

  def COMMA                   = ","
  def DQUOTE                  = "\""
  def DQUOTE2: Parser[String] = "\"\"" ^^ (_ => "\"") // combine 2 dquotes into 1
  def CRLF: Parser[String]    = "\r\n" | "\n"
  def TXT: Regex              = "[^\",\r\n]".r
  def SPACES: Regex           = "[ \t]+".r

  def file: Parser[List[List[String]]] = repsep(record, CRLF) <~ (CRLF?)

  def record: Parser[List[String]] = repsep(field, COMMA)

  def field: Parser[String] = escaped|nonescaped

  def escaped: Parser[String] = {
    ((SPACES?)~>DQUOTE~>((TXT|COMMA|CRLF|DQUOTE2)*)<~DQUOTE<~(SPACES?)) ^^ (ls => ls.mkString(""))
  }

  def nonescaped: Parser[String] = (TXT*) ^^ (ls => ls.mkString(""))

  def parse(s: String): List[List[String]] = parseAll(file, s) match {
    case Success(res, _) => res
    case e => throw new Exception(e.toString)
  }
}

object GoogleDoc extends Logging {
  def getCsvForDoc(docUrl:URL, ws: WSClient): Future[List[List[String]]] = {
    getUrlData(docUrl, ws).map(CSV.parse)
  }

  def getUrlData(url:URL, ws: WSClient, cookies: Map[String, String] = Map.empty, redirects:Int = 0): Future[String] = {
    assert(redirects < 5, "Too many redirects")
    val redirectStatus = Set(MULTIPLE_CHOICES, MOVED_PERMANENTLY, FOUND, SEE_OTHER, NOT_MODIFIED, USE_PROXY, TEMPORARY_REDIRECT)
    val headers: Seq[(String, String)] = if (cookies.isEmpty) Seq.empty else {
      Seq(HeaderNames.COOKIE -> cookies.map { case (name, value) => s"$name=$value" }.mkString("; "))
    }
    ws.url(url.toString)
      .withFollowRedirects(false)
      .withHttpHeaders(headers: _*)
      .get().flatMap { response =>
      if (redirectStatus.contains(response.status)) {
        // follow redirect
        val redirectURL = response.header(HeaderNames.LOCATION).map { location =>
          //val decodedLocation = URLDecoder.decode(location ,"utf-8")
          val target = new URL(url, location)
          target
        }
        assert(redirectURL.isDefined, s"Bad location redirect from ${url.toString}")
        assert(Set("http", "https").contains(redirectURL.get.getProtocol), "Illegal redirect protocol")

        val newCookies = response.cookies.flatMap { cookie =>
          Some(cookie.name -> cookie.value)
        }.toMap

        getUrlData(redirectURL.get, ws, cookies ++ newCookies, redirects + 1)
      } else {
        Future.successful(response.body)
      }
    }
  }
}