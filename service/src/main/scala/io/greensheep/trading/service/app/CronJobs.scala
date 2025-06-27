package io.greensheep.trading.service.app

import zio.ZIO
import com.cronutils.model.Cron

import java.time.LocalTime
import cron4zio.*
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.service.services.{QuotesServices, ScreenerServices}
import zio.durationInt

object CronJobs {


  val UpdateDailyQuotes: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] =
    QuotesServices.DailyQuotesRetrieval

  val RunScreens: ZIO[MongoDatabase, Throwable, Unit] = {
    ScreenerServices.ScreenerTask
  }

  val CheckDatesInconsistencies: ZIO[Any, Nothing, Unit] = {
    ZIO.logInfo("Checking dates inconsistencies") *>
      ZIO.sleep(5.seconds) *>
      ZIO.logInfo("Dates inconsistencies check completed")
  }



  val start: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = for {
    _ <- repeatEffectForCron(UpdateDailyQuotes, unsafeParse("0 0 0 * * ?")).catchAllCause(th => ZIO.logErrorCause("Failed", th)).fork
    _ <- repeatEffectForCron(CheckDatesInconsistencies, unsafeParse("0 0 1 * * ?")).fork
    _ <- repeatEffectForCron(RunScreens, unsafeParse("0 0 2 * * ?")).fork
  } yield ()


}
