package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.screens.{BreadthIndicators as MarketBreadth, TrendScreens}
import io.greensheep.trading.core.types.{DailyAggregate, DailyQuoteData, ScreenStats, Stock, StockMarket, Watchlist}
import io.greensheep.trading.dataservice.MongoDataRepo
import zio.{Chunk, ZIO, ZLayer}

import java.time.LocalDate

object ScreenerServices {

  val LoadQuotes: ZIO[MongoDatabase, Throwable, StockMarket] = for {
    universe <- MongoDataRepo.getStockUniverse
    allQuotes <- ZIO
      .foreachPar(universe.symbols) { symbol =>
        MongoDataRepo.getPriceData(symbol).map(price => symbol -> price).either
      }
      .withParallelism(8)
    errors = allQuotes.collect { case Left(error) => error }
    loaded = allQuotes.collect { case Right(pair) => pair }
    _ <- ZIO.logError(s"Errors while loading quotes: ${errors.size}").when(errors.nonEmpty)
    stocks = loaded.filter(_._2.quotes.nonEmpty).map { case (symbol, data) =>
      Stock(
        symbol = symbol,
        companyName = symbol,
        description = "",
        primaryExchange = "",
        ipoDate = LocalDate.now(),
        dailyAggregates = Chunk.fromIterable(data.quotes.map(DailyAggregate.apply))
      )
    }
  } yield StockMarket(stocks.map(stock => stock.symbol -> stock).toMap)

  def buildWatchlist(name: String, date: LocalDate)(stocks: Seq[(Stock, ScreenStats)]): ZIO[Any, Throwable, Watchlist] =
    ZIO.succeed {
      Watchlist(
        day = date,
        screenName = name,
        tickers = stocks.map(_._1.symbol),
        stats = stocks.map(_._2)
      )
    }

  def buildScreens(date: LocalDate): ZIO[StockMarket, Throwable, Seq[Watchlist]] =
    ZIO.collectAll(
      Seq(
        TrendScreens.SEPA.flatMap(buildWatchlist("SEPA", date)),
        TrendScreens.VolatilityContractionPattern.flatMap(buildWatchlist("VCP", date)),
        TrendScreens.SevenPctUp.flatMap(buildWatchlist("7_PCT_UP", date)),
        TrendScreens.SevenPctDown.flatMap(buildWatchlist("7_PCT_DOWN", date)),
        TrendScreens.New52WeekHigh.flatMap(buildWatchlist("NEW_52_WEEK_HIGH", date)),
        TrendScreens.New52WeekLow.flatMap(buildWatchlist("NEW_52_WEEK_LOW", date)),
        TrendScreens.NewIPOs.flatMap(buildWatchlist("NEW_IPOS", date))
      )
    )

  def getScreensForDate(date: LocalDate): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    val stockMarket = ZLayer.fromZIO(LoadQuotes.map(_.historicalSnapshot(date)))
    buildScreens(date).provideLayer(stockMarket)
  }

  def buildScreensForDate(date: LocalDate): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] =
    for {
      _ <- ZIO.logInfo(s"Building screens for date: $date")
      watchlists <- getScreensForDate(date)
      _ <- ZIO.foreachDiscard(watchlists)(MongoDataRepo.saveWatchlist)
      _ <- ZIO.logInfo(s"Saved ${watchlists.size} watchlists for date: $date")
    } yield watchlists

  def buildScreensForMarket(date: LocalDate, market: StockMarket): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    val snapshot = market.historicalSnapshot(date)
    for {
      watchlists <- buildScreens(date).provideLayer(ZLayer.succeed(snapshot))
      _ <- ZIO.foreachDiscard(watchlists)(MongoDataRepo.saveWatchlist)
    } yield watchlists
  }

  val ScreenerTask: ZIO[MongoDatabase, Throwable, Unit] = ZIO.logSpan("screener-task") {
    val asOf = LocalDate.now().minusDays(1)
    for {
      _ <- ZIO.logInfo(s"Running screener task for $asOf")
      market <- LoadQuotes
      snapshot = market.historicalSnapshot(asOf)
      watchlists <- buildScreens(asOf).provideLayer(ZLayer.succeed(snapshot))
      _ <- ZIO.foreachDiscard(watchlists)(MongoDataRepo.saveWatchlist)
      _ <- ZIO.logInfo(s"Saved ${watchlists.size} watchlists")
      _ <- if (snapshot.stocks.nonEmpty) {
        val indicators = MarketBreadth.fromStockMarket(snapshot).copy(date = asOf)
        MongoDataRepo
          .saveMarketBreadth(indicators)
          .catchAll(error => ZIO.logError(s"Failed to save market breadth: ${error.getMessage}"))
      } else {
        ZIO.logWarning("Skipping market breadth because the snapshot has no stocks")
      }
    } yield ()
  }
}
