package io.greensheep.providers.massive.requests

import io.greensheep.providers.massive.datatypes.{ApiResponse, Ticker}
import zio.ZIO
import zio.http.*
import zio.json.*


case class TickersRequest(
                      ticker: Option[String] = None,
                      _type: String = "CS",
                      market: Option[String] = Some("stocks"),
                      exchange: Option[String] = None,
                      cusip: Option[String] = None,
                      cik: Option[String] = None,
                      date: Option[String] = None,
                      search: Option[String] = None,
                      active: Boolean = true,
                      order: String = "asc",
                      limit: Int = 100,
                      sort: Option[String] = None
                    ) extends ApiRequest[Seq[Ticker]]{

  def buildRequest(baseUrl: String): URL = {

    val requestUrl = s"$baseUrl/v3/reference/tickers"
    var params = QueryParams(
      "type" -> _type,
      "active" -> active.toString,
      "order" -> order,
      "limit" -> limit.toString
    )
    ticker.foreach(x => params = params.add("ticker", x))
    market.foreach(x => params = params.add("market", x))
    exchange.foreach(x => params = params.add("exchange", x))
    cusip.foreach(x => params = params.add("cusip", x))
    cik.foreach(x => params = params.add("cik", x))
    date.foreach(x => params = params.add("date", x))
    search.foreach(x => params = params.add("search", x))
    sort.foreach(x => params = params.add("sort", x))

    URL.decode("https://api.massive.com/v3/reference/tickers?type=CS&market=stocks&active=true&order=asc&limit=1000").toOption.get

  }

  def perform(): ZIO[Client, Throwable, ApiResponse[Seq[Ticker]]] = {
    for {
      client <- ZIO.service[Client]
      url = (client.url / "v3" / "reference" / "tickers")
        .addQueryParams(QueryParams(
          "type" -> _type,
          "active" -> active.toString,
          "order" -> order,
          "limit" -> limit.toString,
          "market" -> market.getOrElse("stocks"),
        ))
      resultString <- MassiveHttp.get(url)
      decoded = resultString.fromJson[ApiResponse[Seq[Ticker]]]
      apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
      _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
    } yield apiResponse
  }
}
