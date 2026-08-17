package io.greensheep.providers.polygon.requests

import io.greensheep.providers.polygon.datatypes.ApiResponse
import io.greensheep.providers.polygon.requests.ApiRequest.PolygonClient
import io.greensheep.providers.polygon.{MarketClient, PolygonSettings, ReferenceClient}
import zio.ZIO
import zio.http.Client

object ApiRequest {
  type PolygonClient = MarketClient & ReferenceClient
}


trait ApiRequest[T] {

  def perform(): ZIO[Client, Throwable, ApiResponse[T]]
}
