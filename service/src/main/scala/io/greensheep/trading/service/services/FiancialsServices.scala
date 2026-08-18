package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.finnhub.client.FinnhubClient
import zio.{Duration, Scope, ZIO, durationInt}
import io.greensheep.trading.dataservice.MongoDataRepo
import io.greensheep.trading.dataservice.FinnhubBridge
import zio.http.Client

import java.time.LocalDate

object FiancialsServices {

  private val perTickerPause: Duration = 1100.millis

  def updateFinancials(
      ticker: String
  ): ZIO[MongoDatabase & FinnhubClient & Client & Scope, Throwable, Unit] =
    ZIO.logSpan("update-financials") {
      for {
        _ <- ZIO.logInfo(s"Updating financials for $ticker")
        remoteApi <- ZIO.service[FinnhubClient]
        financials <- remoteApi.basicFinancials(ticker)
        annual = FinnhubBridge.translateAnnualFinancials(financials)
        quarterly = FinnhubBridge.translateQuarterlyFinancials(financials)
        _ <-
          if annual.isEmpty && quarterly.isEmpty then
            ZIO.unit
          else
            MongoDataRepo.updateFinancials(ticker, annual, quarterly)
      } yield ()
    }

  def updateFinancials(): ZIO[MongoDatabase & FinnhubClient & Client & Scope, Throwable, Unit] =
    ZIO.logSpan("update-financials") {
      for {
        _ <- ZIO.logInfo("Updating financials")
        universe <- MongoDataRepo.getStockUniverse
        _ <- ZIO.foreachDiscard(universe.symbols) { symbol =>
          updateFinancials(symbol)
            .catchAll(error => ZIO.logError(s"Error updating financials for $symbol: ${error.getMessage}"))
            .zipRight(ZIO.sleep(perTickerPause))
        }
        _ <- MongoDataRepo.saveStockUniverse(universe.copy(lastUpdateFinancials = LocalDate.now()))
      } yield ()
    }
}
