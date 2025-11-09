package io.greensheep.trading.service.services

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import zio.ZIO
import io.greensheep.trading.dataservice.MongoDataRepo
import io.greensheep.trading.dataservice.FinnhubBridge
import java.time.LocalDate
import io.greensheep.trading.dataservice.StockUniverse

object FiancialsServices {

  def intializeUniverse()
      : ZIO[MongoDatabase & FinnhubClient, Throwable, StockUniverse] =
    ZIO.logSpan("initialize-universe") {
      MongoDataRepo.getStockUniverse.catchSome {
        case e: NoSuchElementException =>
          ZIO.logInfo(s"No stock universe found, creating new one")
          for {
            _ <- ZIO.logInfo("Getting stock symbols from Finnhub")
            client <- ZIO.service[FinnhubClient]
            symbols <- client.stockSymbols().map(_.filter(_.`type` == "Common Stock").filter(s => Set("XNAS", "XNYS").contains(s.mic)))
            _ <- ZIO.logInfo(s"Found ${symbols.size} symbols")
            universe = StockUniverse(
              _id = "stock-universe",
              name = "stock-universe",
              dataSource = "finnhub",
              lastUpdateQuotes = LocalDate.of(1970, 1, 1),
              lastUpdateFinancials = LocalDate.of(1970, 1, 1),
              timeZone = "America/New_York",
              symbols = symbols.map(_.symbol)
            )
            _ <- MongoDataRepo.saveStockUniverse(universe)
          } yield universe
      }
    }

  def updateFinancials(
      ticker: String
  ): ZIO[MongoDatabase & FinnhubClient, Throwable, Unit] =
    ZIO.logSpan("update-financials") {
      for {
        _ <- ZIO.logInfo(s"Updating financials for $ticker")
        remoteApi <- ZIO.service[FinnhubClient]
        financials <- remoteApi.basicFinancials(ticker)
        _ <- MongoDataRepo.updateFinancials(
          ticker,
          FinnhubBridge.translateAnnualFinancials(financials),
          FinnhubBridge.translateQuarterlyFinancials(financials)
        )
      } yield ()
    }

  def updateFinancials(): ZIO[MongoDatabase & FinnhubClient, Throwable, Unit] =
    ZIO.logSpan("update-financials") {
      for {
        _ <- ZIO.logInfo("Updating financials")
        universe <- intializeUniverse()
        remoteApi <- ZIO.service[FinnhubClient]
        _ <- ZIO.foreach(universe.symbols)(s => updateFinancials(s).catchAll(e => ZIO.logError(s"Error updating financials ${e.getMessage}")))
      } yield ()
    }

}
