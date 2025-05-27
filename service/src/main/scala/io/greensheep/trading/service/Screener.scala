package io.greensheep.trading.service

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.trading.core.screens.SepaScreens
import io.greensheep.trading.core.types.{DailyAggregate, DailyQuoteData, Stock, StockMarket, Watchlist}
import zio.ZIOAppDefault
import io.greensheep.trading.dataservice.{MongoDataRepo, MongoDbDataRepo, PolygonDataSource}
import zio.ZIOAppDefault
import zio.*
import zio.interop.catz.*
import zio.http.Client
import zio.logging.backend.SLF4J

import java.time.LocalDate

object Screener extends ZIOAppDefault  {


  val getAggregatesFromMongo: ZIO[MongoDatabase, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {

      universe <- MongoDataRepo.getStockUniverse
      effects = (universe.symbols.map(s => MongoDataRepo.getPriceData(s).map(pd => s -> pd)))
      allAggs <- ZIO.foreach(effects)(_.either).map(_.foldLeft((Seq.empty[Throwable], Seq.empty[(String, DailyQuoteData)])) {
        case ((ts, xs), Left(t))  => (ts :+ t, xs)
        case ((ts, xs), Right(x)) => (ts, xs :+ x)
      })
      filtered = allAggs._2.filter(_._2.quotes.nonEmpty).map(s => Stock(
        symbol = s._1,
        companyName = s._1,
        description = "",
        primaryExchange = "",
        ipoDate = LocalDate.now(),
        dailyAggregates = Chunk.fromIterable(s._2.quotes.map(DailyAggregate.apply))
      ))
      stockMarket = StockMarket(filtered.map(s => s.symbol -> s).toMap)
      sepaScreens = SepaScreens(stockMarket)
      results = sepaScreens.screen()
      _ = results.foreach(s => println(s.symbol + ","))
      wl = Watchlist(day = java.time.LocalDate.now(),
        screenName = "SEPA", tickers = results.map(_.symbol))
      _ = MongoDataRepo.saveWatchlist(wl)
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  private val logger: ZLayer[Any, Nothing, Unit] = SLF4J.slf4j

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    val logging = Runtime.removeDefaultLoggers ++ logger

    val mongoLayer = MongoClient.live("mongodb://127.0.0.1:27017") >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("polygon")))
    getAggregatesFromMongo.provide(mongoLayer ++ logging)

  }
}
