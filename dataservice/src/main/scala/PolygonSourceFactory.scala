package org.greensheep.trading.dataservice

import io.polygon.kotlin.sdk.rest.{AggregatesParameters, PolygonRestClient}
import io.polygon.kotlin.sdk.rest.reference.SupportedTickersParametersBuilder
import org.greensheep.trading.core.types.Quote
import zio.{RIO, Task, ZIO}

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.IteratorHasAsScala

class PolygonSourceFactory(apiKey: String) {
  val polygonClient = new PolygonRestClient(apiKey)

  def buildGetSymbols(): Task[Seq[String]] = {
    val params = new SupportedTickersParametersBuilder().ticker("").`type`("CS").limit(1000).build()
    val iterator = polygonClient.getReferenceClient.listSupportedTickers(params)
    iterator.getCurrentPage
    val getter = () => polygonClient.getReferenceClient.listSupportedTickers(params).asScala.to(Iterable).toSeq

    val task = ZIO.attempt(getter().map(_.getTicker))
    println("got tickers")
    task
  }

  def buildGetQuotes(ticker: String): Task[Seq[Quote]] = {
    val from = LocalDate.now().minusDays(1000).format(DateTimeFormatter.ISO_LOCAL_DATE)
    val to = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val params = new AggregatesParameters(ticker, 1, "day", from, to, false, 1000L)
    ZIO.serviceWithZIO
    ZIO attempt {
      polygonClient.getAggregatesBlocking(params).getResults.iterator().asScala.toSeq map {
        adt =>
          val instance = java.time.Instant.ofEpochMilli(adt.getTimestampMillis);
          val localDate = java.time.LocalDate
            .ofInstant(instance, java.time.ZoneId.of("America/New_York"));
          Quote(localDate, adt.getOpen, adt.getHigh, adt.getLow, adt.getClose, adt.getVolume.intValue())
      }
    }
  }

}
