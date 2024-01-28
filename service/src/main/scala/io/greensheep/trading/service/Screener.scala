package io.greensheep.trading.service

import io.greensheep.trading.core.screens.SepaScreens
import io.greensheep.trading.core.types.{StockMarket, Watchlist}
import zio.ZIOAppDefault
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.ZIOAppDefault
import zio.*
import zio.Config.LocalDate
import zio.interop.catz.*
import zio.http.Client

object Screener extends ZIOAppDefault  {


  val getAggregatesFromMongo: ZIO[MongoDbDataRepo, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      repo <- ZIO.service[MongoDbDataRepo]
      tickers = repo.getTickers
      allAggs = tickers.map(e => repo.getAggregates(e.symbol).runCollect.map(r => e.copy(dailyAggregates = r.sortBy(_.t))))
      results <- allAggs.runCollect
      stocks <- ZIO.collectAll(results)
      filtered = stocks.filter(!_.dailyAggregates.isEmpty)
      stockMarket = StockMarket(filtered.map(s => s.symbol -> s).toMap)
      sepaScreens = SepaScreens(stockMarket)
      results = sepaScreens.sepaScreens()
      _ = results.foreach(s => println(s.symbol + ","))
      wl = Watchlist(day = java.time.LocalDate.now().toString,
        screenName = "SEPA", dataSource = "polygon", tickers = results.map(_.symbol))
      _ = repo.saveWatchlist(wl)
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    getAggregatesFromMongo.provide(MongoDbDataRepo.layer)

  }
}
