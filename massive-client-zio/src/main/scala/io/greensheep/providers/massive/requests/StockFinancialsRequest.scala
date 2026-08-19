package io.greensheep.providers.massive.requests


import io.greensheep.providers.massive.{MarketClient, ReferenceClient}
import io.greensheep.providers.massive.datatypes.financials.FinancialData
import io.greensheep.providers.massive.requests.ApiRequest.MassiveClient
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
