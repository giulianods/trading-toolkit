package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.jquants.{JquantsClient, JquantsSettings, JquantsUniverse}
import io.greensheep.trading.dataservice.{JquantsBridge, MongoDataRepo}
import zio.*
import zio.http.Client

import java.time.LocalDate

object JquantsQuotesServices {

  def asOfDate: LocalDate =
    LocalDate.now().minusDays(1 + JquantsSettings.DataDelayDays)

  def dailyQuotesRetrieval: ZIO[MongoDatabase & JquantsClient & Client, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo(s"Retrieving J-Quants quotes through as-of $asOfDate (delay=${JquantsSettings.DataDelayDays}d)")
      universe <- MongoDataRepo.getStockUniverse
      start = universe.lastUpdateQuotes.plusDays(1)
      end = asOfDate
      lastWritten <- fetchDateRange(universe.symbols.toSet, start, end)
      _ <- lastWritten match {
        case Some(date) => MongoDataRepo.saveStockUniverse(universe.copy(lastUpdateQuotes = date))
        case None => ZIO.logWarning("No J-Quants quote dates were written; leaving lastUpdateQuotes unchanged")
      }
    } yield ()

  /**
   * Fetch each calendar day once (all tickers), then write quotes for symbols in the universe.
   * Prefer this over per-ticker history on the free 5 req/min plan.
   */
  def fetchDateRange(
      universeTickers: Set[String],
      start: LocalDate,
      end: LocalDate
  ): ZIO[MongoDatabase & JquantsClient & Client, Throwable, Option[LocalDate]] =
    if (end.isBefore(start)) ZIO.succeed(None)
    else {
      for {
        client <- ZIO.service[JquantsClient]
        days = QuotesServices.datesBetweenInclusive(start, end)
        wrote <- Ref.make(Option.empty[LocalDate])
        _ <- ZIO.foreachDiscard(days) { day =>
          client.barsForDate(day).foldZIO(
            error => ZIO.logError(s"J-Quants bars for $day failed: ${error.getMessage}"),
            rows => {
              val byTicker = JquantsUniverse.barsByTicker(rows).view.filterKeys(universeTickers.contains).toMap
              ZIO.foreachDiscard(byTicker.toSeq) { case (ticker, bars) =>
                val quotes = bars.map(JquantsBridge.barToQuote)
                if (quotes.isEmpty) ZIO.unit
                else MongoDataRepo.updateDailyQuotes(ticker, quotes).unit
              } *> wrote.set(Some(day)).when(byTicker.nonEmpty).unit
            }
          )
        }
        last <- wrote.get
      } yield last
    }

  def fetchTickerRange(
      symbols: Seq[(String, String)],
      start: LocalDate,
      end: LocalDate
  ): ZIO[MongoDatabase & JquantsClient & Client, Throwable, Option[LocalDate]] =
    if (end.isBefore(start)) ZIO.succeed(None)
    else {
      for {
        client <- ZIO.service[JquantsClient]
        wrote <- ZIO.foldLeft(symbols)(false) { (any, pair) =>
          val (ticker, code) = pair
          client.history(code, start, end).foldZIO(
            error => ZIO.logError(s"J-Quants history failed for $ticker ($code): ${error.getMessage}").as(any),
            bars => {
              val quotes = bars.map(JquantsBridge.barToQuote)
              if (quotes.isEmpty) ZIO.succeed(any)
              else MongoDataRepo.updateDailyQuotes(ticker, quotes).as(true)
            }
          )
        }
      } yield if (wrote) Some(end) else None
    }
}
