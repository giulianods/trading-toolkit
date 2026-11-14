package io.greensheep.trading.service.app

import io.greensheep.trading.core.types.Markets
import cron4zio.unsafeParse
import java.time.{Instant, ZoneId}
import zio.*
import zio.test.*

object CronGuardSpec extends ZIOSpecDefault {

  override def spec = suite("cron job guard")(
    test("Alpaca evening refresh uses New York time in winter and summer") {
      val cron = unsafeParse(Markets.UsAlpaca.cron.quotes)
      val zone = ZoneId.of(Markets.UsAlpaca.timeZone)
      val winter = Instant.parse("2026-11-10T20:30:00Z")
      val summer = Instant.parse("2026-08-03T20:30:00Z")
      assertTrue(
        winter.plus(CronJobs.nextRunDelay(cron, zone, winter)) == Instant.parse("2026-11-11T01:30:00Z"),
        summer.plus(CronJobs.nextRunDelay(cron, zone, summer)) == Instant.parse("2026-08-04T00:30:00Z")
      )
    },
    test("next evening refresh follows the daylight saving transition") {
      val cron = unsafeParse(Markets.UsAlpaca.cron.quotes)
      val zone = ZoneId.of(Markets.UsAlpaca.timeZone)
      val spring = Instant.parse("2026-03-08T01:31:00Z")
      val autumn = Instant.parse("2026-11-01T00:31:00Z")
      assertTrue(
        spring.plus(CronJobs.nextRunDelay(cron, zone, spring)) == Instant.parse("2026-03-09T00:30:00Z"),
        autumn.plus(CronJobs.nextRunDelay(cron, zone, autumn)) == Instant.parse("2026-11-02T01:30:00Z")
      )
    },
    test("cron wait uses the ZIO clock and sleeps until the actual next instant") {
      val now = Instant.parse("2026-11-10T20:30:00Z")
      (for {
        _ <- TestClock.setTime(now)
        fiber <- CronJobs.sleepUntilNextRun(unsafeParse(Markets.UsAlpaca.cron.quotes), ZoneId.of(Markets.UsAlpaca.timeZone)).fork
        _ <- TestClock.adjust(4.hours)
        before <- fiber.poll
        _ <- TestClock.adjust(1.hour)
        _ <- fiber.join
      } yield assertTrue(before.isEmpty))
    },
    test("a failed job is recorded and the next tick can start") {
      val failing = ZIO.fail(new RuntimeException("boom"))
      (for {
        _ <- CronJobs.guarded("quotes", failing)
        afterFailure <- AppHealth.snapshot
        _ <- CronJobs.guarded("quotes", ZIO.unit)
        afterSuccess <- AppHealth.snapshot
      } yield {
        val failed = afterFailure.find(_.name == "quotes")
        val succeeded = afterSuccess.find(_.name == "quotes")
        assertTrue(
          failed.exists(_.lastError.contains("boom")),
          failed.exists(!_.running),
          succeeded.exists(_.lastSuccess.nonEmpty),
          succeeded.exists(!_.running)
        )
      }).provide(AppHealth.layer)
    },
    test("US Massive job names are scoped by market") {
      val market = Markets.UsMassive
      assertTrue(
        market.id.job("quotes") == "quotes:us-massive",
        market.id.job("splits") == "splits:us-massive",
        market.id.job("clean") == "clean:us-massive",
        market.id.job("screens") == "screens:us-massive",
        market.id.job("financials") == "financials:us-massive",
        market.cron.clean.contains("0 30 2 * * ?")
      )
    }
  )
}
