package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.providers.massive.datatypes.Ticker
import io.greensheep.trading.core.types.{MarketDefinition, Quote, StockDescription}
import io.greensheep.trading.dataservice.{MongoDataRepo, StockUniverse}
import zio.*
import zio.http.Client

import java.time.{Instant, LocalDate}

object AlpacaQuotesServices {
  private val epoch = LocalDate.ofEpochDay(0)

  def refresh(market: MarketDefinition): ZIO[MongoDatabase & Client & MassiveAsyncApi, Throwable, Unit] =
    for {
      assets <- AlpacaClient.assets
      reference <- ZIO.serviceWithZIO[MassiveAsyncApi](_.listCommonStocks)
      _ <- refreshAssets(market, eligibleAssets(assets, reference))
    } yield ()

  private[service] def eligibleAssets(assets: Seq[AlpacaClient.Asset], reference: Seq[Ticker]): Seq[AlpacaClient.Asset] = {
    val common = reference.iterator.filter(_._type == "CS").map(_.ticker).toSet
    assets.filter(asset => (asset.exchange == "NYSE" || asset.exchange == "NASDAQ") && common.contains(asset.symbol))
  }

  private[service] def refreshAssets(market: MarketDefinition, assets: Seq[AlpacaClient.Asset])
      : ZIO[MongoDatabase & Client, Throwable, Unit] =
    for {
      now <- Clock.instant
      end = AlpacaClient.latestSessionCandidate(now)
      historyStart = end.minusMonths(QuotesServices.DefaultHistoryMonths)
      previous <- MongoDataRepo.getStockUniverse.map(Some(_)).catchSome { case _: NoSuchElementException => ZIO.none }
      _ <- ZIO.fail(new IllegalStateException("No eligible NYSE or Nasdaq common shares found for Alpaca")).when(assets.isEmpty)
      symbols = assets.map(_.symbol)
      known = previous.fold(Set.empty[String])(_.symbols.toSet)
      (existing, added) = symbols.partition(known.contains)
      start = previous.fold(historyStart)(_.lastUpdateQuotes.minusDays(7))
      recent <- ingest(existing, start, end, now, checkAdjustments = true)
      backfill <- ingest(added, historyStart, end, now, checkAdjustments = false)
      last <- ZIO.fromOption((recent ++ backfill ++ previous.map(_.lastUpdateQuotes)).maxOption)
        .orElseFail(new IllegalStateException("Alpaca returned no daily SIP bars"))
      _ <- previous match {
        case Some(_) => MongoDataRepo.updateUniverseQuotes(symbols, last)
        case None => MongoDataRepo.saveStockUniverse(
          StockUniverse("stock-universe", "stock-universe", market.dataSource, last, epoch, market.timeZone, symbols)
        )
      }
      _ <- ZIO.logInfo(s"Alpaca refreshed ${symbols.size} symbols through $last")
      _ <- ScreenerServices.latestSessionScreenerTask
      descriptions <- MongoDataRepo.getAllStockDescriptions.map(_.map(stock => stock.symbol -> stock).toMap)
      _ <- ZIO.foreachParDiscard(assets) { asset =>
        val description = StockDescription(asset.symbol, asset.name, epoch, primaryExchange = asset.exchange)
        MongoDataRepo.saveStockDescription(description).unless(descriptions.get(asset.symbol).contains(description))
      }.withParallelism(8)
    } yield ()

  def initialize(market: MarketDefinition): ZIO[MongoDatabase & Client & MassiveAsyncApi, Throwable, Unit] =
    refresh(market) *> MongoDataRepo.getStockUniverse.flatMap(universe =>
      InitializationService.initializeScreenHistory(universe.lastUpdateQuotes, rebuildExisting = true)
    )

  private def ingest(
      symbols: Seq[String],
      start: LocalDate,
      end: LocalDate,
      now: Instant,
      checkAdjustments: Boolean
  ): ZIO[MongoDatabase & Client, Throwable, Option[LocalDate]] =
    ZIO.foldLeft(symbols.grouped(AlpacaClient.batchSize).toSeq)(Option.empty[LocalDate]) { (latest, batch) =>
      for {
        bars <- AlpacaClient.bars(batch, start, end, now)
        repairs <- ZIO.foreachPar(bars.toSeq) { case (ticker, incoming) =>
          val existing =
            if (checkAdjustments)
              MongoDataRepo.getPriceData(ticker).map(_.quotes).catchSome { case _: NoSuchElementException => ZIO.succeed(Seq.empty) }
            else ZIO.succeed(Seq.empty)
          existing.flatMap { stored =>
            val repairFrom = stored.headOption.map(_.day).filter(_ => adjustmentChanged(stored, incoming))
            if (repairFrom.isDefined) ZIO.succeed(repairFrom.map(ticker -> _))
            else MongoDataRepo.updateDailyQuotes(ticker, incoming).as(None)
          }
        }.withParallelism(8)
        _ <- ZIO.foreachDiscard(repairs.flatten) { case (ticker, from) =>
          for {
            replacement <- AlpacaClient.bars(Seq(ticker), from, end, now)
            quotes <- ZIO.fromOption(replacement.get(ticker).filter(_.nonEmpty))
              .orElseFail(new IllegalStateException(s"No adjusted history returned for $ticker"))
            _ <- MongoDataRepo.replaceDailyQuotes(ticker, quotes)
          } yield ()
        }
        batchLatest = bars.valuesIterator.flatten.map(_.day).maxOption
      } yield (latest ++ batchLatest).maxOption
    }

  private[service] def adjustmentChanged(stored: Seq[Quote], incoming: Seq[Quote]): Boolean = {
    val closeByDay = stored.iterator.map(quote => quote.day -> quote.c).toMap
    incoming.exists(quote => closeByDay.get(quote.day).exists(close => math.abs(close - quote.c) > math.abs(close) * 0.001))
  }
}
