package io.greensheep.trading.service.services

import io.finnhub.client.FinnhubClient
import io.finnhub.client.types.StockSymbol
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.{IncomeStatements, MarketDefinition}
import io.greensheep.trading.dataservice.MongoDataRepo
import io.greensheep.trading.service.app.{AppHealth, CronJobs}
import zio.*
import zio.http.Client
import zio.json.ast.Json

import java.time.Instant
import java.time.temporal.ChronoUnit

object FinnhubServices {
  private[service] case class StatementFetch(statements: IncomeStatements, failedFrequencies: Seq[String])

  private[service] def fetchStatements[R](
      fetch: String => ZIO[R, Throwable, Json.Obj], previous: IncomeStatements
  ): URIO[R, StatementFetch] =
    for {
      annual <- fetch("annual").either
      quarterly <- fetch("quarterly").either
      empty = Json.Obj("data" -> Json.Arr())
      decoded = FinnhubIncomeStatements.decode(annual.getOrElse(empty), quarterly.getOrElse(empty))
      // Preserve a successful frequency when the other fails, and retain older quarters
      // that cannot be reconstructed without the unavailable annual/YTD report.
      quarters = if (annual.isLeft || quarterly.isLeft)
        (previous.quarterly ++ decoded.quarterly).groupBy(_.date).toSeq.sortBy(_._1).map(_._2.last)
      else decoded.quarterly
      statements = IncomeStatements(if (annual.isLeft) previous.annual else decoded.annual, quarters)
      failed = Seq("annual" -> annual, "quarterly" -> quarterly).collect { case (frequency, Left(_)) => frequency }
    } yield StatementFetch(statements, failed)

  private[service] def eligibleSymbols(listings: Seq[StockSymbol]): Seq[StockSymbol] =
    listings.filter(row => Set("XNYS", "XNAS").contains(row.mic) && row.`type` == "Common Stock" &&
      "[A-Za-z0-9][A-Za-z0-9.-]{0,31}".r.matches(row.symbol)).distinctBy(_.symbol).sortBy(_.symbol)

  def refreshQuotes(market: MarketDefinition): ZIO[MongoDatabase & FinnhubClient & Client & Scope, Throwable, Unit] =
    for {
      finnhub <- ZIO.service[FinnhubClient]
      listings <- finnhub.stockSymbols()
      eligible = eligibleSymbols(listings).map(_.symbol).toSet
      assets <- AlpacaClient.assets
      common = assets.filter(asset => Set("NYSE", "NASDAQ").contains(asset.exchange) && eligible.contains(asset.symbol))
      _ <- ZIO.fail(new IllegalStateException("Finnhub returned no NYSE/Nasdaq common stocks available on Alpaca"))
        .when(common.isEmpty)
      _ <- ZIO.logInfo(s"US-Finnhub: ${common.size} eligible companies; price history source is Alpaca")
      _ <- AlpacaQuotesServices.refreshAssets(market, common.sortBy(_.symbol))
    } yield ()

  def refreshFinancials: ZIO[MongoDatabase & FinnhubClient & Client & Scope, Throwable, Unit] =
    MongoDataRepo.getStockUniverse.flatMap(universe => refreshFinancialsFor(universe.symbols)) *>
      Clock.currentDateTime.flatMap(now => MongoDataRepo.updateUniverseFinancialsDate(now.toLocalDate))

  def refreshFinancialsFor(symbols: Seq[String]): ZIO[MongoDatabase & FinnhubClient & Client & Scope, Throwable, Unit] =
    for {
      finnhub <- ZIO.service[FinnhubClient]
      now <- Clock.instant
      pending <- ZIO.filter(symbols.distinct) { ticker =>
        MongoDataRepo.getCachedIncomeStatements(ticker).map(!_.exists(_.fetchedAt.plus(7, ChronoUnit.DAYS).isAfter(now)))
      }
      _ <- ZIO.logInfo(s"US-Finnhub: fetching annual and quarterly statements for ${pending.size} companies; " +
        s"minimum ${math.ceil(pending.size * 2.2 / 60).toInt} minutes plus response time and retries")
      outcomes <- ZIO.foreach(pending.zipWithIndex) { case (ticker, index) =>
        for {
          previous <- MongoDataRepo.getCachedIncomeStatements(ticker)
          result <- fetchStatements(frequency => finnhub.financialsReported(ticker, frequency),
            previous.map(_.statements).getOrElse(IncomeStatements.empty))
          fetchedAt <- Clock.instant
          _ <- MongoDataRepo.cacheIncomeStatements(MongoDataRepo.CachedIncomeStatements(ticker,
            if (result.failedFrequencies.isEmpty) fetchedAt else Instant.EPOCH, result.statements))
          _ <- ZIO.logWarning(s"US-Finnhub: $ticker ${result.failedFrequencies.mkString("/")} statements failed after retries; " +
            "preserving available data and continuing; this company remains due for refresh")
            .when(result.failedFrequencies.nonEmpty)
          _ <- ZIO.logInfo(s"US-Finnhub financials: ${index + 1}/${pending.size} companies processed")
            .when((index + 1) % 50 == 0 || index + 1 == pending.size)
        } yield Option.when(result.failedFrequencies.nonEmpty)(ticker)
      }
      failed = outcomes.flatten
      _ <- ZIO.fail(new IllegalStateException(
        s"Finnhub statements incomplete for ${failed.size} companies: ${failed.take(40).mkString(", ")}; available data preserved, failed companies remain due for refresh"
      )).when(failed.nonEmpty)
    } yield ()

  def initialize(market: MarketDefinition): ZIO[MongoDatabase & FinnhubClient & Client & Scope & AppHealth, Throwable, Unit] =
    refreshQuotes(market) *> CronJobs.guarded(market.id.job("financials"), refreshFinancials) *> MongoDataRepo.getStockUniverse.flatMap(universe =>
      InitializationService.initializeScreenHistory(universe.lastUpdateQuotes, rebuildExisting = true)
    )
}
