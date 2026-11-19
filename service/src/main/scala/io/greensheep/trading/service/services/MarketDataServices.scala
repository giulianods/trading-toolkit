package io.greensheep.trading.service.services

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.providers.eodhd.EodhdClient
import io.greensheep.providers.jquants.JquantsClient
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.providers.xetra.XetraClient
import io.greensheep.trading.core.types.{MarketDefinition, MarketId}
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.{AppHealth, CronJobs}
import zio.*
import zio.http.Client

/** Provider capabilities are selected from the profile, independently of its storage/API identity. */
final class MarketDataServices private (client: MongoClient, store: MarketStore, financialGate: Semaphore) {
  import MarketDataServices.*

  def refreshPrices(market: MarketDefinition): ZIO[Environment & MongoDatabase, Throwable, Unit] =
    market.sources.prices match {
      case "massive" => QuotesServices.DailyQuotesRetrieval *> ScreenerServices.latestSessionScreenerTask
      case "alpaca" if market.sources.listings == "finnhub" => FinnhubServices.refreshQuotes(market)
      case "alpaca" => AlpacaQuotesServices.refresh(market)
      case "bvb" => BvbQuotesServices.dailyQuotesRetrieval
      case "xetra" => XetraQuotesServices.dailyQuotesRetrieval
      case "jquants" => JquantsQuotesServices.dailyQuotesRetrieval
      case "eodhd" => EodhdQuotesServices.refresh(market)
      case other => ZIO.fail(new IllegalArgumentException(s"Unsupported price provider '$other'"))
    }

  def initializePrices(market: MarketDefinition): ZIO[Environment & MongoDatabase, Throwable, Unit] =
    market.sources.prices match {
      case "massive" => InitializationService.initializeApp(market)
      case "alpaca" => refreshPrices(market) *> MongoDataRepo.getStockUniverse.flatMap(universe =>
        InitializationService.initializeScreenHistory(universe.lastUpdateQuotes, rebuildExisting = true))
      case "bvb" => BvbInitialization.initializeApp(market)
      case "xetra" => XetraInitialization.initializeApp(market)
      case "jquants" => JquantsInitialization.initializeApp(market)
      case "eodhd" => EodhdQuotesServices.initialize(market)
      case other => ZIO.fail(new IllegalArgumentException(s"Unsupported price provider '$other'"))
    }

  def refreshFinancials(market: MarketDefinition): ZIO[Environment & MongoDatabase, Throwable, Unit] =
    market.sources.financials match {
      case "finnhub" => financialGate.withPermit {
        for {
          universe <- MongoDataRepo.getStockUniverse
          _ <- FinnhubServices.refreshFinancialsFor(universe.symbols)
            .provideSomeLayer[FinnhubClient & Client & Scope](ZLayer.succeed(store.financialsDatabase(market)))
          today <- Clock.currentDateTime.map(_.toLocalDate)
          _ <- MongoDataRepo.updateUniverseFinancialsDate(today)
        } yield ()
      }
      case _ => ZIO.unit // EODHD statements load on demand; none disables statements.
    }

  def start(market: MarketDefinition, skipInitialization: Boolean, skipCron: Boolean): ZIO[Environment, Throwable, Unit] = {
    val dbLayer = ZLayer.succeed(store.database(market))
    val financials = CronJobs.guarded(market.id.job("financials"), refreshFinancials(market))
      .when(market.sources.financials == "finnhub").unit
    val initialize = CronJobs.required(market.id.job("quotes"), initializePrices(market)) *> financials
    val nativeCron = market.sources.prices match {
      case "massive" => CronJobs.start(market.copy(hasFinancials = false))
      case "alpaca" => CronJobs.startPriceProfile(market, refreshPrices(market))
      case "bvb" => CronJobs.startBvb(market)
      case "xetra" => CronJobs.startXetra(market)
      case "jquants" => CronJobs.startJquants(market)
      case "eodhd" => CronJobs.startEodhd(market)
      case _ => ZIO.unit
    }
    (for {
      _ <- ZIO.logInfo(s"Opening profile ${market.id.id}: ${market.sources}, database ${market.id.databaseName}")
      _ <- MongoDataRepo.migrateLegacyUsIfNeeded(client).when(market.id == MarketId.UsMassive)
      _ <- MongoDataRepo.ensureIndexes
      universe <- MongoDataRepo.getStockUniverse.map(Some(_)).catchSome { case _: NoSuchElementException => ZIO.none }
      _ <- ZIO.fromEither(MarketDataServices.validateStoredPrices(market, universe.map(_.dataSource)))
      existing = universe.exists(_.symbols.nonEmpty)
      backfill = !skipInitialization || market.sources.prices == "alpaca" && !existing
      _ <- CronJobs.guarded(market.id.job("initialization"), initialize).forkDaemon.when(backfill)
      _ <- CronJobs.guarded(market.id.job("screens"), ScreenerServices.latestSessionScreenerTask)
        .forkDaemon.unless(backfill)
      _ <- (nativeCron *> CronJobs.startFinancialProfile(market, refreshFinancials(market))).unless(skipCron)
    } yield ()).provideSomeLayer[Environment](dbLayer)
  }
}

object MarketDataServices {
  type Environment = MassiveAsyncApi & BvbClient & XetraClient & JquantsClient & EodhdClient & AppHealth & FinnhubClient & Client & Scope
  def make(client: MongoClient, store: MarketStore): UIO[MarketDataServices] =
    Semaphore.make(1).map(new MarketDataServices(client, store, _))

  private[service] def validateStoredPrices(market: MarketDefinition, stored: Option[String]): Either[Throwable, Unit] = {
    val aliases = Map("finnhub" -> "alpaca", "polygon" -> "massive")
    stored.filter(_.nonEmpty).filter(value => aliases.getOrElse(value, value) != market.sources.prices) match {
      case Some(value) => Left(new IllegalStateException(
        s"Profile ${market.id.id} already contains $value price history; use a new profile ID to select ${market.sources.prices} prices"))
      case None => Right(())
    }
  }
}
