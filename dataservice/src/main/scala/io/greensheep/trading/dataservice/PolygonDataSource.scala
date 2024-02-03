package io.greensheep.trading.dataservice

import io.greensheep.providers.polygon.datatypes.{Aggregate, Ticker}
import io.greensheep.providers.polygon.{MarketClient, PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.{AggregatesRequest, GroupedDailyRequest, StockFinancialsRequest, TickerDetailsRequest, TickersRequest}
import io.polygon.kotlin.sdk.DefaultJvmHttpClientProvider
import io.polygon.kotlin.sdk.rest.{AggregatesParameters, PolygonRestApiCallback, PolygonRestClient}
import io.polygon.kotlin.sdk.rest.reference.{SupportedTickersParameters, SupportedTickersParametersBuilder, TickersDTO}
import io.greensheep.trading.core.services.DataSource
import io.greensheep.trading.core.types.{AnnualFinancialData, QuarterlyFinancialData, Quote, Stock}
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

    )
  }

  val polygonClient = new PolygonRestClient(apiKey)

  def getTickers: ZStream[Any, Throwable, Stock] = {
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

  def getStock(symbol: String): Task[Stock]= {
    val referenceClient = new ReferenceClient(PolygonSettings())
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
    val marketClient = new MarketClient(PolygonSettings())
    val request = AggregatesRequest(stocksTicker = ticker, from = "2022-01-01", to = "2024-01-20", sort = "desc")
    marketClient.getAggregates(request)
  }

  def getFinancialsYearly(ticker: String):  ZStream[Any, Throwable, AnnualFinancialData] = {
    println(s"Get financials for $ticker")
    val referenceClient = new ReferenceClient(PolygonSettings())
    val request = StockFinancialsRequest(ticker, limit = 50)
    referenceClient.getStockFinancials(request)
      .mapError(th => new scala.RuntimeException(s"Failed to get data for $ticker", th))
      .filter(_.fiscalPeriod == "FY")
      .filter(_.financials.incomeStatement.isDefined) map {
      data =>
        AnnualFinancialData(
          ticker = ticker,
          year = data.fiscalYear.toInt,
          revenues = data.financials.incomeStatement
            .flatMap(_.revenues).map(_.value).getOrElse(0.0),
          basicEarningsPerShare = data.financials.incomeStatement
            .flatMap(_.basicEarningsPerShare).map(_.value).getOrElse(0.0),
          dilutedEarningsPerShare = data.financials.incomeStatement
            .flatMap(_.dilutedEarningsPerShare).map(_.value).getOrElse(0.0),
          netCashFlow = data.financials.cashFlowStatement.flatMap(_.netCashFlow).map(_.value).getOrElse(0.0),
          netCashFlowFromOperatingActivities =
            data.financials.cashFlowStatement.flatMap(_.netCashFlowFromOperatingActivities).map(_.value).getOrElse(0.0),
          netCashFlowFromInvestingActivities =
            data.financials.cashFlowStatement.flatMap(_.netCashFlowFromInvestingActivities).map(_.value).getOrElse(0.0),
          netCashFlowFromFinancingActivities =
            data.financials.cashFlowStatement.flatMap(_.netCashFlowFromFinancingActivities).map(_.value).getOrElse(0.0)
        )
    }
  }

  def getFinancialsQuarterly(ticker: String): ZStream[Any, Throwable, QuarterlyFinancialData] = {
    println(s"Get financials for $ticker")
    val referenceClient = new ReferenceClient(PolygonSettings())
    val request = StockFinancialsRequest(ticker, limit = 30)
    referenceClient.getStockFinancials(request)
      .mapError(th => new scala.RuntimeException(s"Failed to get data for $ticker", th))
      .filter(_.fiscalPeriod != "FY")
      .filter(_.fiscalPeriod != "TTM")
      .filter(_.financials.incomeStatement.isDefined) map {
      data =>

        QuarterlyFinancialData(
          quarter = data.fiscalPeriod,
          year = data.fiscalYear.toInt,
          revenues = data.financials.incomeStatement
            .flatMap(_.revenues).map(_.value).getOrElse(0.0),
          basicEarningsPerShare = data.financials.incomeStatement
            .flatMap(_.basicEarningsPerShare).map(_.value).getOrElse(0.0),
          dilutedEarningsPerShare = data.financials.incomeStatement
            .flatMap(_.dilutedEarningsPerShare).map(_.value).getOrElse(0.0),


          netCashFlow = data.financials.cashFlowStatement.flatMap(_.netCashFlow).map(_.value).getOrElse(0.0),
          netCashFlowFromOperatingActivities =
            data.financials.cashFlowStatement.flatMap(_.netCashFlowFromOperatingActivities).map(_.value).getOrElse(0.0),
          netCashFlowFromInvestingActivities =
            data.financials.cashFlowStatement.flatMap(_.netCashFlowFromInvestingActivities).map(_.value).getOrElse(0.0),
          netCashFlowFromFinancingActivities =
            data.financials.cashFlowStatement.flatMap(_.netCashFlowFromFinancingActivities).map(_.value).getOrElse(0.0)
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
