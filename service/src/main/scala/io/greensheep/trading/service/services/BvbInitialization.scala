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

  def initializeApp(market: MarketDefinition): ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo(s"Initializing market ${market.id.id}")
      _ <- initializeUniverse(market)
      _ <- initializeQuotes(market)
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
            lastUpdateQuotes = LocalDate.now().minusMonths(23),
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

  private def initializeQuotes(market: MarketDefinition): ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      universe <- MongoDataRepo.getStockUniverse
      start = universe.lastUpdateQuotes
      end = LocalDate.now().minusDays(1)
      lastWritten <- BvbQuotesServices.fetchRange(universe.symbols, start, end)
      _ <- MongoDataRepo.saveStockUniverse(
        universe.copy(lastUpdateQuotes = lastWritten.getOrElse(universe.lastUpdateQuotes))
      )
    } yield ()

  private def initializeDescriptions: ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      universe <- MongoDataRepo.getStockUniverse
      client <- ZIO.service[BvbClient]
      existing <- MongoDataRepo.getAllStockDescriptions.map(_.map(_.symbol).toSet)
      missing = universe.symbols.filterNot(existing.contains)
      _ <- ZIO.foreachDiscard(missing) { ticker =>
        client.symbolInfo(ticker).flatMap { info =>
          MongoDataRepo.saveStockDescription(BvbBridge.toDescription(info)).unit
        }.catchAll(error => ZIO.logWarning(s"BVB description skipped for $ticker: ${error.getMessage}"))
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
