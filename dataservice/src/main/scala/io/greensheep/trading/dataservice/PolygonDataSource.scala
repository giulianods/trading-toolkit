package io.greensheep.trading.dataservice

import io.greensheep.providers.polygon.datatypes.{Aggregate, Ticker}
import io.greensheep.providers.polygon.{MarketClient, PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.{AggregatesRequest, TickersRequest}
import io.polygon.kotlin.sdk.DefaultJvmHttpClientProvider
import io.polygon.kotlin.sdk.rest.{AggregatesParameters, PolygonRestApiCallback, PolygonRestClient}
import io.polygon.kotlin.sdk.rest.reference.{SupportedTickersParameters, SupportedTickersParametersBuilder, TickersDTO}
import io.greensheep.trading.core.services.DataSource
import io.greensheep.trading.core.types.{Quote, Stock}
import zio.http.Client
import zio.stream.ZStream
import zio.{IO, Schedule, Task, ULayer, ZIO, ZLayer}

import java.time.{Instant, LocalDate, Month}
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.{IteratorHasAsScala, ListHasAsScala}
import scala.language.implicitConversions

object PolygonDataSource{

  val layer: ULayer[PolygonDataSource] = ZLayer.succeed(build())

  def build() = {
    val apiKey = System.getenv("POLYGON_API_KEY")
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


  def getSymbols: Task[Seq[String]] = {
    ZIO.succeed(Seq.empty)
  }

  def getStock(symbol: String): IO[Throwable, Stock]= ???

  def getStocks(): Map[String, Stock]= ???


  def getDailyAggregates(ticker: String): ZIO[Client, Throwable, Seq[Aggregate]] = {
    val marketClient = new MarketClient(PolygonSettings())
    val request = AggregatesRequest(stocksTicker = ticker, from = "2022-01-01", to = "2024-01-07", sort = "desc")
    marketClient.getAggregates(request)
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
