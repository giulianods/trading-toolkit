package io.greensheep.trading.service.app

import zio.ZIO
import com.cronutils.model.Cron
import java.time.LocalTime
import cron4zio._

object CronJobs {


  val UpdateDailyQuotes: ZIO[Any, Nothing, Unit] = ZIO.succeed(())

  val RunScreens: ZIO[Any, Nothing, Unit] = ZIO.succeed(())

  val CheckDatesInconsistencies: ZIO[Any, Nothing, Unit] = ZIO.succeed(())



  val start: ZIO[Any, Throwable, Unit] = for {
    _ <- repeatEffectForCron(UpdateDailyQuotes, unsafeParse("0 0 0 * * ?")).unit
    _ <- repeatEffectForCron(CheckDatesInconsistencies, unsafeParse("0 0 1 * * ?")).unit
    _ <- repeatEffectForCron(RunScreens, unsafeParse("0 0 2 * * ?")).unit
  } yield ()

}
