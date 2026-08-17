package io.greensheep.trading.service.app

import cron4zio.*
import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.service.services.{FiancialsServices, QuotesServices, ScreenerServices}
import zio.*
import zio.http.Client

object CronJobs {

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
    repeatEffectForCron(guarded(name, job), unsafeParse(cron))
      .catchAllCause(cause => ZIO.logErrorCause(s"Cron fiber $name stopped", cause).as(0L))
      .forever
      .forkDaemon
      .unit

  def start: ZIO[MongoDatabase & PolygonAsyncApi & AppHealth & FinnhubClient & Client & Scope, Throwable, Unit] =
    for {
      _ <- AppHealth.markCronStarted
      _ <- supervised("quotes", "0 0 0 * * ?", QuotesServices.DailyQuotesRetrieval)
      _ <- supervised(
        "splits",
        "0 0 1 * * ?",
        QuotesServices.rectifySplits(java.time.LocalDate.now().minusDays(14), java.time.LocalDate.now().minusDays(1))
      )
      _ <- supervised("financials", "0 15 0 * * ?", FiancialsServices.updateFinancials())
      _ <- supervised("screens", "0 0 2 * * ?", ScreenerServices.ScreenerTask)
    } yield ()
}
