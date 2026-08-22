package io.greensheep.providers.jquants

import io.greensheep.trading.core.utils.HttpStatusException
import zio.*
import zio.http.{Client, Header, Headers, Method, Request, URL}

final case class JquantsHttpResponse(status: Int, body: String, headers: Map[String, String])

object JquantsHttp {

  def get(rawUrl: String, apiKey: String): ZIO[Client, Throwable, JquantsHttpResponse] =
    for {
      url <- decode(rawUrl)
      response <- executeGet(url, apiKey)
    } yield response

  def requireOk(response: JquantsHttpResponse): IO[Throwable, String] =
    if (response.status >= 200 && response.status < 300) ZIO.succeed(response.body)
    else ZIO.fail(HttpStatusException(response.status, response.body))

  private def executeGet(url: URL, apiKey: String): ZIO[Client, Throwable, JquantsHttpResponse] =
    ZIO.scoped {
      for {
        client <- ZIO.service[Client]
        response <- client.request(
          Request(
            url = url,
            method = Method.GET,
            headers = Headers(
              Header.Custom("User-Agent", JquantsSettings.UserAgent),
              Header.Custom("Accept", "application/json"),
              Header.Custom("x-api-key", apiKey)
            )
          )
        )
        body <- response.body.asString
        headers = response.headers.toSeq.map(h => h.headerName -> h.renderedValue).toMap
      } yield JquantsHttpResponse(response.status.code, body, headers)
    }

  private def decode(raw: String): IO[Throwable, URL] =
    ZIO.fromEither(URL.decode(raw)).mapError(err => new RuntimeException(s"Invalid J-Quants URL '$raw': $err"))
}
