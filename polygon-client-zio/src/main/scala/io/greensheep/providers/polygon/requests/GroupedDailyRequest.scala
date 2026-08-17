package io.greensheep.providers.polygon.requests

import io.greensheep.providers.polygon.MarketClient
import io.greensheep.providers.polygon.datatypes.{Aggregate, ApiResponse}
import io.greensheep.providers.polygon.requests.ApiRequest.PolygonClient
import zio.ZIO
import zio.http.{Client, Method, Request}
import zio.json.*



case  class GroupedDailyRequest(
                               date: String,
                               adjusted: Boolean = true,
                               includeOtc: Boolean = false
                               ) extends ApiRequest[Seq[Aggregate]]{

  def buildQuery(baseUrl: String): String = {
    s"$baseUrl/v2/aggs/grouped/locale/us/market/stocks/$date?adjusted=$adjusted&include_otc=$includeOtc"
  }


  override def perform(): ZIO[Client, Throwable, ApiResponse[Seq[Aggregate]]] = {
    for {
      client <- ZIO.service[Client]
      _ <- ZIO.logInfo(s"Performing request grouped daily $date")
      url = (client.url / "v2/aggs/grouped/locale/us/market/stocks" / date)
      resultString <- PolygonHttp.get(url)
      decoded = resultString.fromJson[ApiResponse[Seq[Aggregate]]]
      apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
      _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
    } yield apiResponse
  }
}
