package io.greensheep.providers.xetra

import io.greensheep.trading.core.utils.HttpStatusException
import zio.*
import zio.http.{Client, Header, Headers, Method, Request, URL}

final case class XetraHttpResponse(status: Int, body: String, headers: Map[String, String])

final class XetraHttp(saltRef: Ref[(String, Long)]) {

  /** Public Deutsche Börse downloads (T7 CSV) do not require FWB trace headers. */
  def getPublic(rawUrl: String): ZIO[Client, Throwable, XetraHttpResponse] =
    for {
      url <- decode(rawUrl)
      response <- executeGet(url, None)
    } yield response

  def get(rawUrl: String): ZIO[Client, Throwable, XetraHttpResponse] =
    for {
      url <- decode(rawUrl)
      wireUrl = url.toJavaURI.toASCIIString
      salt <- currentSalt
      signed = Some(XetraTraceHeaders.signedHeaders(wireUrl, salt))
      response <- executeGet(url, signed)
      refreshed <-
        if (response.status == 401 || response.status == 403)
          refreshSalt.flatMap { fresh =>
            executeGet(url, Some(XetraTraceHeaders.signedHeaders(wireUrl, fresh)))
          }
        else ZIO.succeed(response)
    } yield refreshed

  private def currentSalt: ZIO[Client, Throwable, String] =
    saltRef.get.flatMap { case (salt, fetchedAt) =>
      val stale = java.lang.System.currentTimeMillis() - fetchedAt > XetraSettings.SaltRefreshMs
      if (salt.nonEmpty && !stale) ZIO.succeed(salt)
      else
        XetraTraceHeaders.fetchSalt.tap { fresh =>
          saltRef.set((fresh, java.lang.System.currentTimeMillis()))
        }
    }

  private def refreshSalt: ZIO[Client, Throwable, String] =
    XetraTraceHeaders.fetchSalt.tap { fresh =>
      saltRef.set((fresh, java.lang.System.currentTimeMillis()))
    }

  private def decode(raw: String): IO[Throwable, URL] =
    ZIO.fromEither(URL.decode(raw)).mapError(err => new RuntimeException(s"Invalid Xetra URL '$raw': ${err}"))

  private def executeGet(url: URL, signed: Option[TraceBundle]): ZIO[Client, Throwable, XetraHttpResponse] =
    ZIO.scoped {
      for {
        client <- ZIO.service[Client]
        baseHeaders = Headers(
          Header.Custom("User-Agent", XetraSettings.UserAgent),
          Header.Custom("Referer", XetraSettings.Referer),
          Header.Custom("Accept", "application/json, text/plain, */*")
        )
        headers = signed match {
          case None => baseHeaders
          case Some(bundle) =>
            baseHeaders ++ Headers(
              Header.Custom("Client-Date", bundle.clientDate),
              Header.Custom("X-Client-TraceId", bundle.traceId),
              Header.Custom("X-Security", bundle.security)
            )
        }
        response <- client.request(Request(url = url, method = Method.GET, headers = headers))
        body <- response.body.asString
        headerMap = response.headers.toSeq.map(h => h.headerName -> h.renderedValue).toMap
      } yield XetraHttpResponse(response.status.code, body, headerMap)
    }
}

object XetraHttp {
  def layer: ZLayer[Any, Nothing, XetraHttp] =
    ZLayer.fromZIO(Ref.make(("", 0L)).map(new XetraHttp(_)))

  def requireOk(response: XetraHttpResponse): IO[Throwable, String] =
    if (response.status >= 200 && response.status < 300) ZIO.succeed(response.body)
    else ZIO.fail(HttpStatusException(response.status, response.body))
}
