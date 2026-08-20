package io.greensheep.providers.massive.requests

import io.greensheep.trading.core.utils.{HttpStatusException, LocalConfig}
import zio.ZIO
import zio.http.{Client, Method, QueryParams, Request, URL}

object MassiveHttp {

  def get(url: URL): ZIO[Client, Throwable, String] =
    ZIO.scoped {
      for {
        client <- ZIO.service[Client]
        authed = withApiKey(client.url, url)
        response <- client.request(Request(url = authed, method = Method.GET))
        body <- response.body.asString
        _ <- ZIO.fail(HttpStatusException(response.status.code, body)).when(response.status.isError)
      } yield body
    }

  private def withApiKey(clientUrl: URL, requestUrl: URL): URL = {
    if (requestUrl.queryParams.get("apiKey").isDefined) requestUrl
    else {
      val key = clientUrl.queryParams.get("apiKey").orElse(LocalConfig.get("massive.api.key"))
      key.map(value => requestUrl.addQueryParams(QueryParams("apiKey" -> value))).getOrElse(requestUrl)
    }
  }
}
