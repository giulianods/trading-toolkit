package io.greensheep.providers.massive.requests

import io.greensheep.providers.massive.datatypes.ApiResponse
import io.greensheep.providers.massive.requests.ApiRequest.MassiveClient
import io.greensheep.providers.massive.{MarketClient, MassiveSettings, ReferenceClient}
import zio.ZIO
import zio.http.Client

object ApiRequest {
  type MassiveClient = MarketClient & ReferenceClient
}


trait ApiRequest[T] {

  def perform(): ZIO[Client, Throwable, ApiResponse[T]]
}
