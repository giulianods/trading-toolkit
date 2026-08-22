package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.jquants.JquantsClient
import io.greensheep.trading.core.screens.BreadthIndicators as MarketBreadth
import io.greensheep.trading.core.types.MarketDefinition
import io.greensheep.trading.dataservice.{JquantsBridge, MongoDataRepo, StockUniverse}
import zio.*
import zio.http.Client

import java.time.LocalDate

object JquantsInitialization {

  def initializeApp(market: MarketDefinition): ZIO[MongoDatabase & JquantsClient & Client, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo(s"Initializing market ${market.id.id}")
      shares <- initializeUniverse(market)
      _ <- initializeQuotes()
      _ <- initializeDescriptions(shares)
        .catchAll(error => ZIO.logWarning(s"J-Quants description initialization skipped: ${error.getMessage}"))
        .forkDaemon
      _ <- initializeHistoricalWatchlists()
      _ <- initializeMarketBreadthHistory()
      _ <- ScreenerServices.ScreenerTask
    } yield ()

  private def initializeUniverse(market: MarketDefinition)
      : ZIO[MongoDatabase & JquantsClient & Client, Throwable, Seq[(String, String)]] =
    for {
      client <- ZIO.service[JquantsClient]
      shares <- client.listMainShares
      symbols = shares.map(_.ticker)
      alreadyRan <- MongoDataRepo.hasStockUniverse
      asOf = JquantsQuotesServices.asOfDate
      _ <- if (!alreadyRan) {
        MongoDataRepo.saveStockUniverse(
          StockUniverse(
            _id = "stock-universe",
            name = "stock-universe",
            dataSource = market.dataSource,
            lastUpdateQuotes = asOf.minusMonths(23),
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
    } yield shares.map(s => s.ticker -> s.code)

  private def initializeQuotes(): ZIO[MongoDatabase & JquantsClient & Client, Throwable, Unit] =
    for {
      universe <- MongoDataRepo.getStockUniverse
      start = universe.lastUpdateQuotes.plusDays(1)
      end = JquantsQuotesServices.asOfDate
      lastWritten <- JquantsQuotesServices.fetchDateRange(universe.symbols.toSet, start, end)
      _ <- MongoDataRepo.saveStockUniverse(
        universe.copy(lastUpdateQuotes = lastWritten.getOrElse(universe.lastUpdateQuotes))
      )
    } yield ()

  private def initializeDescriptions(shares: Seq[(String, String)])
      : ZIO[MongoDatabase & JquantsClient & Client, Throwable, Unit] =
    for {
      universe <- MongoDataRepo.getStockUniverse
      client <- ZIO.service[JquantsClient]
      existing <- MongoDataRepo.getAllStockDescriptions.map(_.map(_.symbol).toSet)
      codeByTicker = shares.toMap
      missing = universe.symbols.filterNot(existing.contains)
      _ <- ZIO.foreachDiscard(missing.take(200)) { ticker =>
        codeByTicker.get(ticker) match {
          case None => ZIO.unit
          case Some(code) =>
            client.instrumentInfo(code).flatMap { info =>
              MongoDataRepo.saveStockDescription(JquantsBridge.toDescription(info)).unit
            }.catchAll(error => ZIO.logWarning(s"J-Quants description skipped for $ticker: ${error.getMessage}"))
        }
      }
    } yield ()

  private def initializeHistoricalWatchlists(): ZIO[MongoDatabase, Throwable, Unit] = {
    val end = JquantsQuotesServices.asOfDate
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
    val end = JquantsQuotesServices.asOfDate
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
