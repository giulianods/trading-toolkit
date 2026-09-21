package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.trading.core.screens.BreadthIndicators as MarketBreadth
import io.greensheep.trading.core.types.MarketDefinition
import io.greensheep.trading.dataservice.{BvbBridge, MongoDataRepo, StockUniverse}
import zio.*
import zio.http.Client

import java.time.LocalDate

object BvbInitialization {

  private val HistoryLookbackMonths: Long = 23
  /** Symbols at or below this bar count are treated as needing a full history backfill. */
  private val ThinHistoryBars: Int = 50

  def initializeApp(market: MarketDefinition): ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo(s"Initializing market ${market.id.id}")
      _ <- initializeUniverse(market)
      _ <- initializeQuotes(market)
      _ <- pruneIlliquidSymbols
      _ <- initializeDescriptions
        .catchAll(error => ZIO.logWarning(s"BVB description initialization skipped: ${error.getMessage}"))
        .forkDaemon
      _ <- initializeHistoricalWatchlists()
      _ <- initializeMarketBreadthHistory()
      _ <- ScreenerServices.ScreenerTask
    } yield ()

  private def initializeUniverse(market: MarketDefinition): ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      client <- ZIO.service[BvbClient]
      shares <- client.listMainShares
      symbols = shares.map(_.ticker)
      alreadyRan <- MongoDataRepo.hasStockUniverse
      _ <- if (!alreadyRan) {
        MongoDataRepo.saveStockUniverse(
          StockUniverse(
            _id = "stock-universe",
            name = "stock-universe",
            dataSource = market.dataSource,
            lastUpdateQuotes = LocalDate.now().minusMonths(HistoryLookbackMonths),
            lastUpdateFinancials = LocalDate.of(1970, 1, 1),
            timeZone = market.timeZone,
            symbols = symbols
          )
        )
      } else {
        MongoDataRepo.getStockUniverse.flatMap { universe =>
          MongoDataRepo.saveStockUniverse(universe.copy(symbols = symbols))
        }
      }
    } yield ()

  /**
   * New or thinly-covered symbols (e.g. AeRO added after the market already existed) get a full
   * lookback. Known symbols only catch up from `lastUpdateQuotes`, so restarts stay cheap.
   */
  private def initializeQuotes(market: MarketDefinition): ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      universe <- MongoDataRepo.getStockUniverse
      end = LocalDate.now().minusDays(1)
      fullStart = end.minusMonths(HistoryLookbackMonths)
      barCounts <- ZIO.foreach(universe.symbols) { ticker =>
        MongoDataRepo
          .getPriceData(ticker)
          .map(_.quotes.size)
          .orElseSucceed(0)
          .map(ticker -> _)
      }
      thin = barCounts.collect { case (ticker, n) if n < ThinHistoryBars => ticker }
      known = universe.symbols.diff(thin)
      _ <- ZIO.logInfo(
        s"BVB quotes init: ${thin.size} new/thin symbols (full ${HistoryLookbackMonths}m), " +
          s"${known.size} known (from ${universe.lastUpdateQuotes})"
      )
      _ <- BvbQuotesServices.fetchRange(thin, fullStart, end).unit
      lastWritten <- BvbQuotesServices.fetchRange(known, universe.lastUpdateQuotes, end)
      caughtUp = lastWritten.getOrElse(universe.lastUpdateQuotes)
      nextUpdate =
        if (thin.nonEmpty && end.isAfter(universe.lastUpdateQuotes)) end
        else if (caughtUp.isAfter(universe.lastUpdateQuotes)) caughtUp
        else universe.lastUpdateQuotes
      _ <- MongoDataRepo.saveStockUniverse(universe.copy(lastUpdateQuotes = nextUpdate))
    } yield ()

  /** Drop names that almost never trade (sparse BVB history / tiny recent volume). */
  private def pruneIlliquidSymbols: ZIO[MongoDatabase, Throwable, Unit] =
    for {
      universe <- MongoDataRepo.getStockUniverse
      assessed <- ZIO.foreach(universe.symbols) { ticker =>
        MongoDataRepo
          .getPriceData(ticker)
          .map(data => ticker -> BvbLiquidityFilter.assess(data.quotes))
          .orElseSucceed(ticker -> BvbLiquidityFilter.assess(Seq.empty))
      }
      liquid = assessed.collect { case (ticker, a) if a.liquid => ticker }.sorted
      dropped = assessed.collect { case (ticker, a) if !a.liquid => ticker -> a.reason.getOrElse("illiquid") }
      _ <- ZIO.logInfo(s"BVB liquidity filter: kept ${liquid.size}/${universe.symbols.size}, dropped ${dropped.size}")
      _ <- ZIO
        .logInfo(s"BVB liquidity drops (sample): ${dropped.take(20).map { case (t, r) => s"$t($r)" }.mkString(", ")}")
        .when(dropped.nonEmpty)
      _ <- MongoDataRepo.saveStockUniverse(universe.copy(symbols = liquid)).when(liquid.nonEmpty)
      _ <- ZIO
        .fail(new IllegalStateException("BVB liquidity filter removed every symbol"))
        .when(liquid.isEmpty)
    } yield ()

  private def initializeDescriptions: ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      universe <- MongoDataRepo.getStockUniverse
      client <- ZIO.service[BvbClient]
      existing <- MongoDataRepo.getAllStockDescriptions.map(_.map(_.symbol).toSet)
      missing = universe.symbols.filterNot(existing.contains)
      _ <- ZIO.foreachDiscard(missing) { ticker =>
        client
          .symbolInfo(ticker)
          .flatMap { info =>
            MongoDataRepo.saveStockDescription(BvbBridge.toDescription(info)).unit
          }
          .catchAll(error => ZIO.logWarning(s"BVB description skipped for $ticker: ${error.getMessage}"))
      }
    } yield ()

  private def initializeHistoricalWatchlists(): ZIO[MongoDatabase, Throwable, Unit] = {
    val end = LocalDate.now().minusDays(1)
    val start = end.minusMonths(3)
    val dates = QuotesServices.datesBetweenInclusive(start, end)
    for {
      market <- ScreenerServices.LoadQuotes
      _ <- ZIO.foreachDiscard(dates) { date =>
        MongoDataRepo.hasWatchlistsForDate(date).flatMap { exists =>
          if (exists) ZIO.unit
          else ScreenerServices.buildScreensForMarket(date, market).ignore
        }
      }
    } yield ()
  }

  private def initializeMarketBreadthHistory(): ZIO[MongoDatabase, Throwable, Unit] = {
    val end = LocalDate.now().minusDays(1)
    val start = end.minusMonths(3)
    val dates = QuotesServices.datesBetweenInclusive(start, end)
    for {
      stockMarket <- ScreenerServices.LoadQuotes
      _ <- ZIO.foreachDiscard(dates) { date =>
        MongoDataRepo.hasMarketBreadthForDate(date).flatMap { exists =>
          if (exists) ZIO.unit
          else {
            val snapshot = stockMarket.historicalSnapshot(date)
            if (snapshot.stocks.nonEmpty)
              MongoDataRepo.saveMarketBreadth(MarketBreadth.fromStockMarket(snapshot).copy(date = date)).unit
            else ZIO.unit
          }
        }
      }
    } yield ()
  }
}
