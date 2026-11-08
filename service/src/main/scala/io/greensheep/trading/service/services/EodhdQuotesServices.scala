package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.eodhd.{EodhdClient, EodhdIssuers}
import io.greensheep.trading.core.types.{MarketDefinition, StockDescription}
import io.greensheep.trading.dataservice.{MongoDataRepo, StockUniverse}
import zio.*
import zio.http.Client

import java.time.{LocalDate, ZoneId}

object EodhdQuotesServices {
  private val epoch = LocalDate.ofEpochDay(0)

  def initialize(market: MarketDefinition): ZIO[MongoDatabase & EodhdClient & Client, Throwable, Unit] =
    refresh(market) *> MongoDataRepo.getStockUniverse.flatMap(universe =>
      InitializationService.initializeScreenHistory(universe.lastUpdateQuotes)
    )

  def refresh(market: MarketDefinition): ZIO[MongoDatabase & EodhdClient & Client, Throwable, Unit] =
    for {
      client <- ZIO.service[EodhdClient]
      now <- Clock.instant
      end = now.atZone(ZoneId.of(market.timeZone)).toLocalDate.minusDays(1)
      start = end.minusMonths(QuotesServices.DefaultHistoryMonths)
      shares <- client.canadianShares
      _ <- ZIO.logInfo(s"EODHD is refreshing ${shares.size} Canadian listings through $end")
      // Reload the window because a split can change every earlier bar for a ticker.
      histories <- ZIO.foreachPar(shares) { share =>
        client.history(share.ticker, start, end).map(share -> _)
      }.withParallelism(8)
      withQuotes = histories.filter(_._2.nonEmpty)
      _ <- ZIO.fail(new IllegalStateException("EODHD returned no daily bars for one or more Canadian exchanges"))
        .when(withQuotes.map(_._1.exchange).toSet != shares.map(_.exchange).toSet)
      last = withQuotes.map(_._2.last.day).max
      descriptions <- MongoDataRepo.getAllStockDescriptions.map(_.map(stock => stock.symbol -> stock).toMap)
      _ <- ZIO.foreachParDiscard(withQuotes) { case (share, quotes) =>
        val description = StockDescription(share.ticker, share.name, epoch, primaryExchange = share.exchange)
        MongoDataRepo.replaceDailyQuotes(share.ticker, quotes) *>
          MongoDataRepo.saveStockDescription(description).unless(descriptions.get(share.ticker).contains(description))
      }.withParallelism(8)
      symbols = withQuotes.map(_._1.ticker)
      _ <- MongoDataRepo.saveStockUniverse(
        StockUniverse("stock-universe", "stock-universe", market.dataSource, last, epoch, market.timeZone, symbols,
          issuerByTicker = Some(EodhdIssuers.byTicker(withQuotes.map(_._1))))
      )
      _ <- ZIO.logInfo(s"EODHD refreshed ${symbols.size} Canadian listings through $last")
      _ <- ScreenerServices.latestSessionScreenerTask
    } yield ()
}
