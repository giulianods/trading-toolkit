package io.greensheep.trading.service.app

import cron4zio.*
import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.service.services.{FiancialsServices, QuotesServices, ScreenerServices}
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

  def start: ZIO[MongoDatabase & PolygonAsyncApi & AppHealth & FinnhubClient & Client & Scope, Throwable, Unit] =
    for {
      _ <- AppHealth.markCronStarted
      _ <- ZIO.logInfo(s"Cron jobs scheduled in local zone $localZone")
      // Polygon grouped-daily is still settling at 16:00 ET close; wait ~30 minutes.
      // On UTC+3 that is 00:30 local (16:30 EST). Later under EDT; still after close.
      _ <- supervised("quotes", "0 30 0 * * ?", QuotesServices.DailyQuotesRetrieval)
      _ <- supervised("splits", "0 0 2 * * ?", rectifyRecentSplits)
      _ <- supervised("financials", "0 15 0 ? * SAT", FiancialsServices.updateFinancials())
      _ <- supervised("screens", "0 0 3 * * ?", ScreenerServices.ScreenerTask)
    } yield ()

  private def rectifyRecentSplits: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] =
    ZIO.suspendSucceed {
      val today = java.time.LocalDate.now(localZone)
      QuotesServices.rectifySplits(today.minusDays(14), today.minusDays(1))
    }
}
