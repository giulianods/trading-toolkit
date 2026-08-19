package io.greensheep.trading.service.app

import io.greensheep.trading.core.types.Markets
import zio.*
import zio.test.*

object CronGuardSpec extends ZIOSpecDefault {

  override def spec = suite("cron job guard")(
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
        market.id.job("screens") == "screens:us-massive",
        market.id.job("financials") == "financials:us-massive"
      )
    }
  )
}
