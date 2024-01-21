package io.greensheep.trading.dataservice

import io.greensheep.providers.polygon.datatypes.{Aggregate, Ticker}
import io.greensheep.providers.polygon.{MarketClient, PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.{AggregatesRequest, GroupedDailyRequest, StockFinancialsRequest, TickersRequest}
import io.polygon.kotlin.sdk.DefaultJvmHttpClientProvider
import io.polygon.kotlin.sdk.rest.{AggregatesParameters, PolygonRestApiCallback, PolygonRestClient}
import io.polygon.kotlin.sdk.rest.reference.{SupportedTickersParameters, SupportedTickersParametersBuilder, TickersDTO}
import io.greensheep.trading.core.services.DataSource
import io.greensheep.trading.core.types.{QuarterlyFinancialData, Quote, Stock}
import io.greensheep.trading.dataservice.PolygonDataSource.layer
import zio.http.Client
import zio.stream.ZStream
import zio.{IO, Schedule, Task, ULayer, ZIO, ZLayer}

import java.time.{Instant, LocalDate, Month, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.{IteratorHasAsScala, ListHasAsScala}
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

  private def toCoreType(ticker: Ticker): Stock = {
    Stock(
      ticker.ticker,
      ticker.name,
      LocalDate.of(1970, Month.JANUARY, 1),
      ""
    )
  }

  val polygonClient = new PolygonRestClient(apiKey)

  def getTickers: ZStream[Client, Throwable, Stock] = {
    val request = TickersRequest(
      _type = "CS",
      market = Some("stocks"),
      limit = 1000
    )
    val referenceClient = new ReferenceClient(
      PolygonSettings()
    )
    referenceClient.getTickers(request).map(toCoreType)
  }

  def getSnapshot(date: String): Task[Map[String, Aggregate]] = {
    println(s"Getting snapshot for $date at ${ZonedDateTime.now()}")
    val request = GroupedDailyRequest(date)
    val marketClient = new MarketClient(PolygonSettings())
    marketClient.getGroupedDaily(request).delay(16.seconds)
  }


  def getSymbols: Task[Seq[String]] = {
    ZIO.succeed(Seq.empty)
  }

  def getStock(symbol: String): IO[Throwable, Stock]= ???

  def getStocks(): Map[String, Stock]= ???


  def getDailyAggregates(ticker: String): ZIO[Client, Throwable, Seq[Aggregate]] = {
    val marketClient = new MarketClient(PolygonSettings())
    val request = AggregatesRequest(stocksTicker = ticker, from = "2022-01-01", to = "2024-01-20", sort = "desc")
    marketClient.getAggregates(request)
  }

  def getFinancials(ticker: String): ZStream[Any, Throwable, QuarterlyFinancialData] = {
    val referenceClient = new ReferenceClient(PolygonSettings())
    val request = StockFinancialsRequest(ticker, limit = 30)
    referenceClient.getStockFinancials(request)
      .filter(_.fiscalPeriod != "FY")
      .filter(_.fiscalPeriod != "TTM")
      .filter(_.financials.incomeStatement.isDefined) map {
      data =>

        QuarterlyFinancialData(
          quarter = data.fiscalPeriod,
          year = data.fiscalYear.toInt,
          revenues = data.financials.incomeStatement.get.revenues.value,
          grossProfit = data.financials.incomeStatement.get.grossProfit.value,
          basicEarningsPerShare = data.financials.incomeStatement.get.basicEarningsPerShare.value,
          dilutedEarningsPerShare = data.financials.incomeStatement.get.dilutedEarningsPerShare.value,
          costOfRevenue = data.financials.incomeStatement.get.costOfRevenue.value,

          netCashFlow = data.financials.cashFlowStatement.map(_.netCashFlow.value).getOrElse(0.0),
          netCashFlowFromOperatingActivities =
            data.financials.cashFlowStatement.map(_.netCashFlowFromOperatingActivities.value).getOrElse(0.0),
          netCashFlowFromInvestingActivities =
            data.financials.cashFlowStatement.map(_.netCashFlowFromInvestingActivities.value).getOrElse(0.0),
          netCashFlowFromFinancingActivities =
            data.financials.cashFlowStatement.map(_.netCashFlowFromFinancingActivities.value).getOrElse(0.0)
        )
    }
  }

  def getQuotes(ticker: String): Task[Seq[Quote]] = {
    val from = LocalDate.now().minusDays(1000).format(DateTimeFormatter.ISO_LOCAL_DATE)
    val to = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val params = new AggregatesParameters(ticker, 1, "day", from, to, false, 1000L)

    ZIO attempt {
      println(s"Getting quotes for $ticker")
      val response = polygonClient.getAggregatesBlocking(params)
      response.getStatus match {
        case "OK" | "DELAYED" =>
          response.getResults.iterator().asScala.toSeq map {
            adt =>
              val instance = java.time.Instant.ofEpochMilli(adt.getTimestampMillis)
              val localDate = java.time.LocalDate
                .ofInstant(instance, java.time.ZoneId.of("America/New_York"))
              Quote(localDate, adt.getOpen, adt.getHigh, adt.getLow, adt.getClose, adt.getVolume.intValue())
          }
        case "ERROR" =>
          println("failed")
          throw new RuntimeException("got api error " + response.getStatus)
        case _ =>
          throw new RuntimeException(response.getStatus)

      }

    }
  }

}
