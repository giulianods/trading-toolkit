package io.greensheep.providers.polygon.requests

import io.greensheep.providers.polygon.datatypes.{ApiResponse, Ticker}
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

    URL.decode("https://api.polygon.io/v3/reference/tickers?type=CS&market=stocks&active=true&order=asc&limit=1000").toOption.get

  }

  def perform(): ZIO[Client, Throwable, ApiResponse[Seq[Ticker]]] = {
    val result = for{
      apiUrl <- ZIO.fromEither(URL.decode("https://api.polygon.io"))
      client <- ZIO.service[Client]
      url = (client.url / "v3" / "reference" / "tickers")
        .addQueryParams(QueryParams(
          "type" -> _type,
          "active" -> active.toString,
          "order" -> order,
          "limit" -> limit.toString,
          "market" -> market.getOrElse("stocks"),
          ))

      theClient = client.copy(url = url)

      decoded <- ZIO.scoped {
        for{
          reqRes <- client.request(Request(url = url, method = Method.GET)).map(_.body.asString)
          resultString <- reqRes
          decoded = resultString.fromJson[ApiResponse[Seq[Ticker]]]
        } yield decoded
      }

      apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
      _ <- ZIO.fail({
        println(s"Error: ${apiResponse.errorMessage}")
        new RuntimeException(apiResponse.errorMessage)
      }).unless(apiResponse.isValid)
      resultEither = apiResponse.results.toRight(new RuntimeException(s"Missing result from json: $decoded"))
      result <- ZIO.fromEither(resultEither)
    } yield apiResponse
    result.mapError(th => {
      th
    })
  }
}
