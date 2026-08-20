package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.xetra.XetraClient
import io.greensheep.trading.dataservice.{MongoDataRepo, XetraBridge}
import zio.*
import zio.http.Client

import java.time.LocalDate

object XetraQuotesServices {

  def dailyQuotesRetrieval: ZIO[MongoDatabase & XetraClient & Client, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo("Retrieving Xetra quotes from the last update through local yesterday")
      universe <- MongoDataRepo.getStockUniverse
      start = universe.lastUpdateQuotes.minusDays(1)
      end = LocalDate.now().minusDays(1)
      lastWritten <- fetchRange(universe.symbols, start, end)
      _ <- lastWritten match {
        case Some(date) => MongoDataRepo.saveStockUniverse(universe.copy(lastUpdateQuotes = date))
        case None => ZIO.logWarning("No Xetra quote dates were written; leaving lastUpdateQuotes unchanged")
      }
    } yield ()

  def fetchRange(
      symbols: Seq[String],
      start: LocalDate,
      end: LocalDate
  ): ZIO[MongoDatabase & XetraClient & Client, Throwable, Option[LocalDate]] =
    if (end.isBefore(start)) ZIO.succeed(None)
    else {
      for {
        client <- ZIO.service[XetraClient]
        shares <- client.listMainShares
        isinByTicker = shares.map(s => s.ticker -> s.isin).toMap
        wrote <- ZIO.foldLeft(symbols)(false) { (any, ticker) =>
          isinByTicker.get(ticker) match {
            case None =>
              ZIO.logWarning(s"Xetra ticker $ticker missing from current universe CSV").as(any)
            case Some(isin) =>
              client.history(isin, start, end).foldZIO(
                error => ZIO.logError(s"Xetra history failed for $ticker ($isin): ${error.getMessage}").as(any),
                bars => {
                  val quotes = bars.map(XetraBridge.barToQuote)
                  if (quotes.isEmpty) ZIO.succeed(any)
                  else MongoDataRepo.updateDailyQuotes(ticker, quotes).as(true)
                }
              )
          }
        }
      } yield if (wrote) Some(end) else None
    }
}
