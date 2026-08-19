package io.greensheep.providers.massive.requests

import io.greensheep.providers.massive.ReferenceClient
import io.greensheep.providers.massive.datatypes.{ApiResponse, TickerDetails}
import io.greensheep.providers.massive.requests.ApiRequest.MassiveClient
import zio.ZIO
import zio.http.{Client, Method, QueryParams, Request}
import zio.json.*

case class TickerDetailsRequest(
                               ticker: String
                               ) extends ApiRequest[TickerDetails]{

  def buildRequest(baseUrl: String): String = {
    s"$baseUrl/v3/reference/tickers/$ticker"
  }

  def perform(): ZIO[Client, Throwable, ApiResponse[TickerDetails]] = {
   for{
     client <- ZIO.service[Client]
     url = (client.url / "v3" / "reference" / "tickers"/ ticker)
     resultString <- MassiveHttp.get(url)
     decoded = resultString.fromJson[ApiResponse[TickerDetails]]
     apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
     _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
     resultEither = apiResponse.results.toRight(new RuntimeException(s"Missing result from json: $decoded"))
     _ <- ZIO.fromEither(resultEither)
   } yield apiResponse
  }
}
