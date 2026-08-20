package io.greensheep.providers.xetra

import zio.*
import zio.http.{Client, Header, Headers, Method, Request, URL}

import java.security.MessageDigest
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.util.matching.Regex

final case class TraceBundle(clientDate: String, traceId: String, security: String)

object XetraTraceHeaders {
  private val MainJsPattern: Regex = """main\.[a-f0-9]+\.js""".r
  private val SaltPattern: Regex = """salt:"([a-z0-9]+)"""".r
  private val UtcFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

  def signedHeaders(fullUrl: String, salt: String): TraceBundle = {
    val nowUtc = Instant.now()
    val clientDate = UtcFormatter.format(nowUtc.atZone(java.time.ZoneOffset.UTC))
    val traceId = md5Hex(clientDate + fullUrl + salt)
    val localMinute = ZonedDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
    val security = md5Hex(localMinute)
    TraceBundle(clientDate, traceId, security)
  }

  def fetchSalt: ZIO[Client, Throwable, String] =
    ZIO.scoped {
      for {
        client <- ZIO.service[Client]
        homeUrl <- decode(XetraSettings.WebsiteUrl)
        homeResponse <- client.request(
          Request(
            url = homeUrl,
            method = Method.GET,
            headers = browserHeaders
          )
        )
        homeHtml <- homeResponse.body.asString
        salt <- extractSaltFromHome(homeHtml) match {
          case Some(value) => ZIO.succeed(value)
          case None          => extractSaltFromMainJs(client, homeHtml)
        }
      } yield salt
    }.catchAll { error =>
      ZIO.logWarning(s"Xetra salt scrape failed (${error.getMessage}); using fallback") *>
        ZIO.succeed(XetraSettings.FallbackSalt)
    }

  private def extractSaltFromHome(homeHtml: String): Option[String] =
    SaltPattern.findFirstMatchIn(homeHtml).map(_.group(1))

  private def extractSaltFromMainJs(client: Client, homeHtml: String): ZIO[Client, Throwable, String] =
    ZIO.fromOption(MainJsPattern.findFirstIn(homeHtml)).flatMap { mainJs =>
      ZIO.scoped {
        for {
          jsUrl <- decode(s"${XetraSettings.WebsiteUrl}$mainJs")
          jsResponse <- client.request(Request(url = jsUrl, method = Method.GET, headers = browserHeaders))
          jsBody <- jsResponse.body.asString
          salt <- ZIO.fromOption(SaltPattern.findFirstMatchIn(jsBody).map(_.group(1))).mapError(_ =>
            new RuntimeException(s"Could not locate boerse-frankfurt tracing salt in $mainJs")
          )
        } yield salt
      }
    }.mapError(_ => new RuntimeException("Could not locate boerse-frankfurt main.js bundle"))

  private val browserHeaders: Headers = Headers(
    Header.Custom("User-Agent", XetraSettings.UserAgent),
    Header.Custom("Referer", XetraSettings.Referer),
    Header.Custom("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
    Header.Custom("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
  )

  private def md5Hex(value: String): String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.digest(value.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  private def decode(raw: String): IO[Throwable, URL] =
    ZIO.fromEither(URL.decode(raw)).mapError(err => new RuntimeException(s"Invalid URL '$raw': $err"))
}
