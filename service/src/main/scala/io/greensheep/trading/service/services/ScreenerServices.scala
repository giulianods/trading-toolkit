package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.screens.{SepaScreens, TrendScreens}
import io.greensheep.trading.core.types.{DailyAggregate, DailyQuoteData, ScreenStats, Stock, StockMarket, Watchlist}
import io.greensheep.trading.dataservice.MongoDataRepo
import zio.{Chunk, ZIO, ZLayer}

import java.time.LocalDate

object ScreenerServices {

  val LoadQuotes: ZIO[MongoDatabase, Throwable, StockMarket] = for {
    universe <- MongoDataRepo.getStockUniverse
    effects = universe.symbols.map(s => MongoDataRepo.getPriceData(s).map(pd => s -> pd))
    allQuotes <- ZIO.foreach(effects)(_.either).map(_.foldLeft((Seq.empty[Throwable], Seq.empty[(String, DailyQuoteData)])) {
      case ((ts, xs), Left(t)) => (ts :+ t, xs)
      case ((ts, xs), Right(x)) => (ts, xs :+ x)
    })
    _ <- if (allQuotes._1.nonEmpty)
      ZIO.logError(s"Errors while loading quotes: ${allQuotes._1.size}")
    else
      ZIO.unit
    stocks = allQuotes._2.filter(_._2.quotes.nonEmpty).map(s => Stock(
      symbol = s._1,
      companyName = s._1,
      description = "",
      primaryExchange = "",
      ipoDate = LocalDate.now(),
      dailyAggregates = Chunk.fromIterable(s._2.quotes.map(DailyAggregate.apply))
    ))
  } yield StockMarket(stocks.map(s => s.symbol -> s).toMap)



  def buildWatchlist(name: String)(stocks: Seq[(Stock, ScreenStats)]): ZIO[Any, Throwable, Watchlist] = ZIO.succeed {
    Watchlist(
      day = java.time.LocalDate.now(),
      screenName = name,
      tickers = stocks.map(_._1.symbol),
      stats = stocks.map(_._2)
    )
  }

  val Screens: ZIO[StockMarket, Throwable, Seq[Watchlist]] = {
    ZIO.collectAll(Seq(
      TrendScreens.SEPA.flatMap(buildWatchlist("SEPA")),
      TrendScreens.VolatilityContractionPattern.flatMap(buildWatchlist("VCP")),
      TrendScreens.SevenPctUp.flatMap(buildWatchlist("7_PCT_UP")),
      TrendScreens.SevenPctDown.flatMap(buildWatchlist("7_PCT_DOWN"))
    ))
  }

  def getScreens: ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    val stockMarket = ZLayer.fromZIO(LoadQuotes)
    Screens.provideLayer(stockMarket)
  }


  def getScreensForDate(date: LocalDate): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    val stockMarket = ZLayer.fromZIO(LoadQuotes.map(_.historicalSnapshot(date)))
    Screens.provideLayer(stockMarket)
  }

  def buildScreensForDate(date: LocalDate): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    for {
      _ <- ZIO.logInfo(s"Building screens for date: $date")
      watchlists <- getScreensForDate(date)
      _ <- ZIO.foreachDiscard(watchlists)(MongoDataRepo.saveWatchlist)
      _ <- ZIO.logInfo(s"Saved ${watchlists.size} watchlists for date: $date")
    } yield watchlists
  }


  val ScreenerTask: ZIO[MongoDatabase, Throwable, Unit] = ZIO.logSpan("screener-task") {
    for {
      _ <- ZIO.logInfo("Running screener task")
      watchlists <- getScreens
      _ <- ZIO.foreach(watchlists)(MongoDataRepo.saveWatchlist)
      _ <- ZIO.logInfo(s"Saved ${watchlists.size} watchlists")
    } yield ()
  }


}
