package io.greensheep.trading.service.services

import io.finnhub.client.types.{BasicFinancials, StockSymbol}
import zio.{Scope, ZIO}
import zio.http.{Client, Request, URL}
import zio.json.*

final case class FinnhubApiClient(private val apiKey: String) {

  def basicFinancials(symbol: String, metric: String = "all"): ZIO[Client & Scope, Throwable, BasicFinancials] = {
    val urlValue = s"https://finnhub.io/api/v1/stock/metric?symbol=$symbol&metric=$metric&token=$apiKey"
    for {
      url <- ZIO
        .fromOption(URL.decode(urlValue).toOption)
        .orElseFail(new IllegalArgumentException(s"Invalid Finnhub URL for symbol '$symbol'"))
      response <- Client.request(Request.get(url))
      body <- response.body.asString
      result <- ZIO
        .fromEither(body.fromJson[BasicFinancials])
        .mapError(msg => new RuntimeException(s"Failed to parse Finnhub basic financials for '$symbol': $msg"))
    } yield result
  }

  def stockSymbols(exchange: String = "US"): ZIO[Client & Scope, Throwable, List[StockSymbol]] = {
    val urlValue = s"https://finnhub.io/api/v1/stock/symbol?exchange=$exchange&token=$apiKey"
    for {
      url <- ZIO
        .fromOption(URL.decode(urlValue).toOption)
        .orElseFail(new IllegalArgumentException(s"Invalid Finnhub URL for exchange '$exchange'"))
      response <- Client.request(Request.get(url))

      body <- response.body.asString
      _ <- ZIO.logInfo(body)
      result <- ZIO
        .fromEither(body.fromJson[List[StockSymbol]])
        .mapError(msg => new RuntimeException(s"Failed to parse Finnhub stock symbols for '$exchange': $msg"))
    } yield result
  }
}
