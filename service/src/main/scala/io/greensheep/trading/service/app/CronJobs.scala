package io.greensheep.trading.service.app

import cron4zio.*
import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.providers.jquants.JquantsClient
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.providers.xetra.XetraClient
import io.greensheep.trading.core.types.MarketDefinition
import io.greensheep.trading.dataservice.MongoDataRepo
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
      job: ZIO[R, Throwable, Unit],
      zone: ZoneId = localZone
  ): URIO[R & AppHealth, Unit] =
    repeatEffectForCron(guarded(name, job), unsafeParse(cron), zoneId = zone)
      .catchAllCause(cause => ZIO.logErrorCause(s"Cron fiber $name stopped", cause).as(0L))
      .forever
      .forkDaemon
      .unit

  def start(
      market: MarketDefinition
  ): ZIO[MongoDatabase & MassiveAsyncApi & AppHealth & FinnhubClient & Client & Scope, Throwable, Unit] = {
    val quotes = market.id.job("quotes")
    val splits = market.id.job("splits")
    val clean = market.id.job("clean")
    val screens = market.id.job("screens")
    val financials = market.id.job("financials")
    val marketZone = ZoneId.of(market.timeZone)
    for {
      _ <- AppHealth.markCronStarted
      _ <- ZIO.logInfo(s"Quote maintenance for ${market.id.id} scheduled in $marketZone")
      _ <- supervised(
        quotes,
        market.cron.quotes,
        QuotesServices.DailyQuotesRetrieval *> guarded(screens, ScreenerServices.massiveScreenerTask),
        marketZone
      )
      _ <- supervised(splits, market.cron.splits, rectifyRecentSplits, marketZone)
      _ <- market.cron.clean match {
        case Some(expr) =>
          supervised(clean, expr, QuotesServices.nightlyHygiene *> guarded(screens, ScreenerServices.massiveScreenerTask), marketZone)
        case None =>
          ZIO.logInfo(s"Skipping clean cron for ${market.id.id}")
      }
      _ <- market.cron.financials match {
        case Some(expr) if market.hasFinancials =>
          supervised(financials, expr, FiancialsServices.updateFinancials())
        case _ =>
          ZIO.logInfo(s"Skipping financials cron for ${market.id.id}")
      }
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
      val end = QuotesServices.latestMassiveDate()
      for {
        universe <- MongoDataRepo.getStockUniverse
        lag = java.time.temporal.ChronoUnit.DAYS.between(universe.lastUpdateQuotes, end)
        _ <- if (lag > QuotesServices.ApiResetMaxQuoteLagDays)
          ZIO.logWarning(
            s"Skipping splits rectify while quotes are ${lag}d behind (lastUpdateQuotes=${universe.lastUpdateQuotes})"
          )
        else
          QuotesServices.rectifySplits(end.minusDays(13), end)
      } yield ()
    }
}
