package io.greensheep.providers.polygon.requests

import io.greensheep.providers.polygon.ReferenceClient
import io.greensheep.providers.polygon.datatypes.{ApiResponse, TickerDetails}
import io.greensheep.providers.polygon.requests.ApiRequest.PolygonClient
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
     resultString <- ZIO.scoped {
       for{
         res <- client.request(Request(url = url, method = Method.GET))
         resultString <- res.body.asString
         _ <- if(res.status.isError)
           ZIO.fail(new RuntimeException(s"Failed ticker details for $ticker: ${res.status.code} - ${res.status.text} : $resultString"))
         else ZIO.unit

       } yield resultString
     }
     decoded = resultString.fromJson[ApiResponse[TickerDetails]]
     apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
     _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
     resultEither = apiResponse.results.toRight(new RuntimeException(s"Missing result from json: $decoded"))
     _ <- ZIO.fromEither(resultEither)
   } yield apiResponse
  }
}
