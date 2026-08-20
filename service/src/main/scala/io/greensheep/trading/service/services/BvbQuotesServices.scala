package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.trading.dataservice.{BvbBridge, MongoDataRepo}
import zio.*
import zio.http.Client

import java.time.LocalDate

object BvbQuotesServices {

  def dailyQuotesRetrieval: ZIO[MongoDatabase & BvbClient & Client, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo("Retrieving BVB quotes from the last update through local yesterday")
      universe <- MongoDataRepo.getStockUniverse
      start = universe.lastUpdateQuotes.minusDays(1)
      end = LocalDate.now().minusDays(1)
      lastWritten <- fetchRange(universe.symbols, start, end)
      _ <- lastWritten match {
        case Some(date) => MongoDataRepo.saveStockUniverse(universe.copy(lastUpdateQuotes = date))
        case None => ZIO.logWarning("No BVB quote dates were written; leaving lastUpdateQuotes unchanged")
      }
    } yield ()

  def fetchRange(
      symbols: Seq[String],
      start: LocalDate,
      end: LocalDate
  ): ZIO[MongoDatabase & BvbClient & Client, Throwable, Option[LocalDate]] =
    if (end.isBefore(start)) ZIO.succeed(None)
    else {
      for {
        client <- ZIO.service[BvbClient]
        wrote <- ZIO.foldLeft(symbols)(false) { (any, ticker) =>
          client.history(ticker, start, end).foldZIO(
            error => ZIO.logError(s"BVB history failed for $ticker: ${error.getMessage}").as(any),
            bars => {
              val quotes = bars.map(BvbBridge.barToQuote)
              if (quotes.isEmpty) ZIO.succeed(any)
              else MongoDataRepo.updateDailyQuotes(ticker, quotes).as(true)
            }
          )
        }
      } yield if (wrote) Some(end) else None
    }
}
