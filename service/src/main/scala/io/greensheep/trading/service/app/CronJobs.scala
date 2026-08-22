package io.greensheep.trading.service.app

import cron4zio.*
import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.providers.jquants.JquantsClient
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.providers.xetra.XetraClient
import io.greensheep.trading.core.types.MarketDefinition
import io.greensheep.trading.service.services.{BvbQuotesServices, FiancialsServices, JquantsQuotesServices, QuotesServices, ScreenerServices, XetraQuotesServices}
import zio.*
import zio.http.Client

import java.time.ZoneId

object CronJobs {

  private val localZone: ZoneId = ZoneId.systemDefault()

  def guarded[R](name: String, job: ZIO[R, Throwable, Unit]): ZIO[R & AppHealth, Nothing, Unit] =
    AppHealth.tryStart(name).flatMap {
      case false =>
        ZIO.logWarning(s"Skipping $name because a previous run is still in progress")
      case true =>
        job
          .tap(_ => AppHealth.finishSuccess(name) *> ZIO.logInfo(s"Job $name completed"))
          .catchAll { error =>
            AppHealth.finishFailure(name, error.getMessage) *>
              ZIO.logError(s"Job $name failed: ${error.getMessage}")
          }
          .onInterrupt(AppHealth.finishFailure(name, "interrupted"))
    }

  private def supervised[R](
      name: String,
      cron: String,
      job: ZIO[R, Throwable, Unit]
  ): URIO[R & AppHealth, Unit] =
    repeatEffectForCron(guarded(name, job), unsafeParse(cron), zoneId = localZone)
      .catchAllCause(cause => ZIO.logErrorCause(s"Cron fiber $name stopped", cause).as(0L))
      .forever
      .forkDaemon
      .unit

  def start(
      market: MarketDefinition
  ): ZIO[MongoDatabase & MassiveAsyncApi & AppHealth & FinnhubClient & Client & Scope, Throwable, Unit] = {
    val quotes = market.id.job("quotes")
    val splits = market.id.job("splits")
    val screens = market.id.job("screens")
    val financials = market.id.job("financials")
    for {
      _ <- AppHealth.markCronStarted
      _ <- ZIO.logInfo(s"Cron jobs for ${market.id.id} scheduled in local zone $localZone")
      _ <- supervised(quotes, market.cron.quotes, QuotesServices.DailyQuotesRetrieval)
      _ <- supervised(splits, market.cron.splits, rectifyRecentSplits)
      _ <- market.cron.financials match {
        case Some(expr) if market.hasFinancials =>
          supervised(financials, expr, FiancialsServices.updateFinancials())
        case _ =>
          ZIO.logInfo(s"Skipping financials cron for ${market.id.id}")
      }
      _ <- supervised(screens, market.cron.screens, ScreenerServices.ScreenerTask)
    } yield ()
  }

  def startBvb(
      market: MarketDefinition
  ): ZIO[MongoDatabase & BvbClient & Client & AppHealth, Throwable, Unit] = {
    val quotes = market.id.job("quotes")
    val screens = market.id.job("screens")
    for {
      _ <- AppHealth.markCronStarted
      _ <- ZIO.logInfo(s"Cron jobs for ${market.id.id} scheduled in local zone $localZone")
      _ <- supervised(quotes, market.cron.quotes, BvbQuotesServices.dailyQuotesRetrieval)
      _ <- ZIO.logInfo(s"Skipping splits cron for ${market.id.id}")
      _ <- ZIO.logInfo(s"Skipping financials cron for ${market.id.id}")
      _ <- supervised(screens, market.cron.screens, ScreenerServices.ScreenerTask)
    } yield ()
  }

  def startXetra(
      market: MarketDefinition
  ): ZIO[MongoDatabase & XetraClient & Client & AppHealth, Throwable, Unit] = {
    val quotes = market.id.job("quotes")
    val screens = market.id.job("screens")
    for {
      _ <- AppHealth.markCronStarted
      _ <- ZIO.logInfo(s"Cron jobs for ${market.id.id} scheduled in local zone $localZone")
      _ <- supervised(quotes, market.cron.quotes, XetraQuotesServices.dailyQuotesRetrieval)
      _ <- ZIO.logInfo(s"Skipping splits cron for ${market.id.id}")
      _ <- ZIO.logInfo(s"Skipping financials cron for ${market.id.id}")
      _ <- supervised(screens, market.cron.screens, ScreenerServices.ScreenerTask)
    } yield ()
  }

  def startJquants(
      market: MarketDefinition
  ): ZIO[MongoDatabase & JquantsClient & Client & AppHealth, Throwable, Unit] = {
    val quotes = market.id.job("quotes")
    val screens = market.id.job("screens")
    for {
      _ <- AppHealth.markCronStarted
      _ <- ZIO.logInfo(s"Cron jobs for ${market.id.id} scheduled in local zone $localZone")
      _ <- supervised(quotes, market.cron.quotes, JquantsQuotesServices.dailyQuotesRetrieval)
      _ <- ZIO.logInfo(s"Skipping splits cron for ${market.id.id}")
      _ <- ZIO.logInfo(s"Skipping financials cron for ${market.id.id}")
      _ <- supervised(screens, market.cron.screens, ScreenerServices.ScreenerTask)
    } yield ()
  }

  private def rectifyRecentSplits: ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    ZIO.suspendSucceed {
      val today = java.time.LocalDate.now(localZone)
      QuotesServices.rectifySplits(today.minusDays(14), today.minusDays(1))
    }
}
