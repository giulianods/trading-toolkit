package io.greensheep.providers.bvb

import io.greensheep.trading.core.utils.HttpStatusException
import zio.*
import zio.http.{Client, Header, Headers, Method, Request, URL}

final case class BvbHttpResponse(status: Int, body: String, headers: Map[String, String])

object BvbHttp {

  val browserHeaders: Headers = Headers(
    Header.Custom("User-Agent", BvbSettings.UserAgent),
    Header.Custom("Referer", BvbSettings.Referer),
    Header.Custom("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
  )

  def get(url: URL): ZIO[Client, Throwable, BvbHttpResponse] =
    ZIO.scoped {
      for {
        client <- ZIO.service[Client]
        response <- client.request(Request(url = url, method = Method.GET, headers = browserHeaders))
        body <- response.body.asString
        headers = response.headers.toSeq.map(h => h.headerName -> h.renderedValue).toMap
      } yield BvbHttpResponse(response.status.code, body, headers)
    }

  def requireOk(response: BvbHttpResponse): IO[Throwable, String] =
    if (response.status >= 200 && response.status < 300) ZIO.succeed(response.body)
    else ZIO.fail(HttpStatusException(response.status, response.body))
}
