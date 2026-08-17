package io.greensheep.trading.dataservice

import io.greensheep.providers.polygon.{MarketClient, PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.{AggregatesRequest, TickersRequest}
import io.greensheep.trading.core.types.Quote
import zio.Task

import java.time.LocalDate
import java.time.format.DateTimeFormatter
class PolygonSourceFactory(apiKey: String) {
  private val settings = PolygonSettings(apiKey = apiKey)

  def buildGetSymbols(): Task[Seq[String]] = {
    val request = TickersRequest(_type = "CS", market = Some("stocks"), limit = 1000)
    new ReferenceClient(settings).getTickers(request).runCollect.map(_.map(_.ticker))
  }

  def buildGetQuotes(ticker: String): Task[Seq[Quote]] = {
    val from = LocalDate.now().minusDays(1000).format(DateTimeFormatter.ISO_LOCAL_DATE)
    val to = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val request = AggregatesRequest(ticker, from = from, to = to, adjusted = false, limit = 1000)
    new MarketClient(settings)
      .getAggregates(request)
      .map(_.map(PolygonBridge.aggregateToQuote))
  }

}
