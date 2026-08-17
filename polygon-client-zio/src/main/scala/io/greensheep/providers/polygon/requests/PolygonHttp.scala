package io.greensheep.providers.polygon.requests

import io.greensheep.trading.core.utils.HttpStatusException
import zio.ZIO
import zio.http.{Client, Method, QueryParams, Request, URL}

object PolygonHttp {

  def get(url: URL): ZIO[Client, Throwable, String] =
    ZIO.scoped {
      for {
        client <- ZIO.service[Client]
        authed = withClientApiKey(client.url, url)
        response <- client.request(Request(url = authed, method = Method.GET))
        body <- response.body.asString
        _ <- ZIO.fail(HttpStatusException(response.status.code, body)).when(response.status.isError)
      } yield body
    }

  private def withClientApiKey(clientUrl: URL, requestUrl: URL): URL =
    clientUrl.queryParams.get("apiKey") match {
      case Some(key) if requestUrl.queryParams.get("apiKey").isEmpty =>
        requestUrl.addQueryParams(QueryParams("apiKey" -> key))
      case _ =>
        requestUrl
    }
}
