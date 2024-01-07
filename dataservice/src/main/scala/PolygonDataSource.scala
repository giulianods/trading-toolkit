package org.greensheep.trading.dataservice

import io.polygon.kotlin.sdk.DefaultJvmHttpClientProvider
import io.polygon.kotlin.sdk.rest.{AggregatesParameters, PolygonRestApiCallback, PolygonRestClient}
import io.polygon.kotlin.sdk.rest.reference.{SupportedTickersParameters, SupportedTickersParametersBuilder, TickersDTO}
import org.greensheep.trading.core.services.DataSource
import org.greensheep.trading.core.types.{Quote, Stock}
import zio.{IO, Schedule, Task, ULayer, ZIO, ZLayer}

import java.time.{Instant, LocalDate}
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.{IteratorHasAsScala, ListHasAsScala}

object PolygonDataSource{

  val layer: ULayer[DataSource] = ZLayer.succeed(build())

  def build() = {
    val apiKey = System.getenv("POLYGON_API_KEY")
    new PolygonDataSource(apiKey)
  }
}


class PolygonDataSource(apiKey: String) extends DataSource{

  val polygonClient = new PolygonRestClient(apiKey)


  def getSymbols: Task[Seq[String]] = {
   // val tickerRequest = TickersRe

    val params = new SupportedTickersParametersBuilder()
      .activeSymbolsOnly(true)
      .market("stocks")
      .ticker("")
      .`type`("CS")
      .limit(1000).build()
    ZIO attempt {
      val results = polygonClient.getReferenceClient.getSupportedTickersBlocking(params)


    }
  }

  def getStock(symbol: String): IO[Throwable, Stock]= ???

  def getStocks(): Map[String, Stock]= ???

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
