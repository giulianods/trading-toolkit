package io.greensheep.providers.massive.requests

import io.greensheep.providers.massive.MassiveSettings
import io.greensheep.trading.core.utils.{HttpStatusException, LocalConfig}
import zio.ZIO
import zio.http.{Client, Method, QueryParams, Request, URL}

object MassiveHttp {

  /** Shared client has no base URL. Relative request paths are resolved against Massive. */
  def absolute(url: URL): URL =
    if (url.host.nonEmpty) url
    else Base ++ url

  def get(url: URL): ZIO[Client, Throwable, String] =
    ZIO.scoped {
      for {
        client <- ZIO.service[Client]
        resolved = absolute(url)
        authed = withApiKey(client.url, resolved)
        shown = s"https://${authed.host.getOrElse("?")}/${authed.path.encode.dropWhile(_ == '/')}"
        _ <- ZIO.logInfo(s"Massive GET $shown")
        response <- client.request(Request(url = authed, method = Method.GET))
        body <- response.body.asString
        _ <- ZIO.fail(HttpStatusException(response.status.code, body)).when(response.status.isError)
      } yield body
    }

  private val Base: URL =
    URL.decode(MassiveSettings.DefaultBaseUrl).getOrElse(URL.empty)

  private def withApiKey(clientUrl: URL, requestUrl: URL): URL = {
    if (requestUrl.queryParams.get("apiKey").isDefined) requestUrl
    else {
      val key = clientUrl.queryParams.get("apiKey").orElse(LocalConfig.get("massive.api.key"))
      key.map(value => requestUrl.addQueryParams(QueryParams("apiKey" -> value))).getOrElse(requestUrl)
    }
  }
}
