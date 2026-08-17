package io.greensheep.providers.polygon.requests


import io.greensheep.providers.polygon.{MarketClient, ReferenceClient}
import io.greensheep.providers.polygon.datatypes.financials.FinancialData
import io.greensheep.providers.polygon.requests.ApiRequest.PolygonClient
import zio.ZIO
import zio.http.Client
import zio.json.*


case class StockFinancialsRequest(
                                 ticker: String,
                                 limit: Int = 10
                                 )  {

  def buildRequest(baseUrl: String): String = {
    s"$baseUrl/vX/reference/financials?ticker=$ticker&limit=$limit"
  }

  def perform(): ZIO[Client, Throwable, Seq[FinancialData]] = {
    ZIO.succeed(Seq.empty)
  }
}
