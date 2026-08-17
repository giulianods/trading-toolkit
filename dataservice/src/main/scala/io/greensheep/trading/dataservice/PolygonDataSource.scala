package io.greensheep.trading.dataservice

import io.greensheep.providers.polygon.datatypes.{Aggregate, Ticker}
import io.greensheep.providers.polygon.{MarketClient, PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.{AggregatesRequest, GroupedDailyRequest, StockFinancialsRequest, TickerDetailsRequest, TickersRequest}
import io.greensheep.trading.core.services.DataSource
import io.greensheep.trading.core.types.{AnnualFinancialData, QuarterlyFinancialData, Quote, Stock}
import io.greensheep.trading.dataservice.PolygonDataSource.layer
import zio.http.Client
import zio.stream.ZStream
import zio.{IO, Schedule, Task, ULayer, ZIO, ZLayer}

import java.time.{Instant, LocalDate, Month, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.language.implicitConversions
import zio.*

object PolygonDataSource{

  val layer: ULayer[PolygonDataSource] = ZLayer.succeed(build())

  def build() = {
    val apiKey = java.lang.System.getenv("POLYGON_API_KEY")
    new PolygonDataSource(apiKey)
  }
}


class PolygonDataSource(apiKey: String) extends DataSource{

  private val settings = PolygonSettings(apiKey = apiKey)

  private def toCoreType(ticker: Ticker): Stock = {
    Stock(
      ticker.ticker,
      ticker.name,
      LocalDate.of(1970, Month.JANUARY, 1),

    )
  }

  def getTickers: ZStream[Any, Throwable, Stock] = {
    val request = TickersRequest(
      _type = "CS",
      market = Some("stocks"),
      limit = 1000
    )
    val referenceClient = new ReferenceClient(
      settings
    )
    referenceClient.getTickers(request).map(toCoreType)
  }

  def getSnapshot(date: String): Task[Map[String, Aggregate]] = {
    println(s"Getting snapshot for $date at ${ZonedDateTime.now()}")
    val request = GroupedDailyRequest(date)
    val marketClient = new MarketClient(settings)
    marketClient.getGroupedDaily(request).delay(16.seconds)
  }


  def getSymbols: Task[Seq[String]] = {
    ZIO.succeed(Seq.empty)
  }

  def getStock(symbol: String): Task[Stock]= {
    val referenceClient = new ReferenceClient(settings)
    referenceClient.getTickerDetails(TickerDetailsRequest(symbol)) map {
      details =>
        Stock(
          symbol = details.ticker,
          companyName = details.name,
          ipoDate = LocalDate.parse(details.listDate.getOrElse("1970-01-01")),
          primaryExchange = details.primaryExchange,
          description = details.description.getOrElse(""),
          totalEmployees = details.totalEmployees.getOrElse(0),
          sicCode = details.sicCode.getOrElse(""),
          sicDescription = details.sicDescription.getOrElse(""),
          marketCap = details.marketCap.map(_.toLong).getOrElse(0L),
          weightedSharesOutstanding = details.weightedSharesOutstanding.getOrElse(0L)
        )
    }

  }

  def getStocks(): Task[Map[String, Stock]]= {
    for{
      symbols <- this.getSymbols
      stocks = symbols.map(s => s -> getStock(s)).toMap
      taskMap <- ZIO.foreach(stocks) {
        case (key, task) => task.map(value => key -> value)
      }.map(_.toMap)
    } yield taskMap
  }


  def getDailyAggregates(ticker: String): ZIO[Client, Throwable, Seq[Aggregate]] = {
    val marketClient = new MarketClient(settings)
    val request = AggregatesRequest(stocksTicker = ticker, from = "2022-01-01", to = "2024-05-12", sort = "desc")
    marketClient.getAggregates(request)
  }



  def getQuotes(ticker: String): Task[Seq[Quote]] = {
    val from = LocalDate.now().minusDays(1000).format(DateTimeFormatter.ISO_LOCAL_DATE)
    val to = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val request = AggregatesRequest(ticker, from = from, to = to, adjusted = false, limit = 1000)
    new MarketClient(settings)
      .getAggregates(request)
      .map(_.map(PolygonBridge.aggregateToQuote))
  }

}
