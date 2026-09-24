package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.{DailyAggregate, Stock}
import zio.Chunk
import zio.test.*

import java.time.LocalDate

object TrendScreenQualitySpec extends ZIOSpecDefault {

  private def bars(n: Int, start: LocalDate, close: Double, low: Double = 1.0): Chunk[DailyAggregate] =
    Chunk.fromIterable(
      (0 until n).map { i =>
        val c = close + i * 0.01
        DailyAggregate(c, c, low, c, 1000, start.plusDays(i.toLong))
      }
    )

  override def spec = suite("TrendScreenQuality")(
    test("rejects series with a long gap") {
      val early = bars(100, LocalDate.of(2025, 1, 1), 5.0)
      val late = bars(120, LocalDate.of(2026, 7, 17), 9.0)
      val stock = Stock("SHOT", "SHOT", LocalDate.of(2020, 1, 1), dailyAggregates = early ++ late)
      assertTrue(!TrendScreenQuality.eligible(stock))
    },
    test("rejects unadjusted reverse-split jump") {
      val before = bars(220, LocalDate.of(2025, 1, 1), 0.7)
      val after = bars(30, LocalDate.of(2025, 1, 1).plusDays(220), 12.0)
      val stock = Stock("NRDY", "NRDY", LocalDate.of(2020, 1, 1), dailyAggregates = before ++ after)
      assertTrue(!TrendScreenQuality.eligible(stock))
    },
    test("rejects zero low / thin history") {
      val stock = Stock(
        "ELOX",
        "ELOX",
        LocalDate.of(2020, 1, 1),
        dailyAggregates = bars(50, LocalDate.of(2026, 1, 1), 18.0, low = 0.0)
      )
      assertTrue(!TrendScreenQuality.eligible(stock))
    },
    test("accepts a clean 200+ bar series") {
      val stock = Stock(
        "TWLO",
        "TWLO",
        LocalDate.of(2020, 1, 1),
        dailyAggregates = bars(220, LocalDate.of(2025, 1, 1), 100.0, low = 80.0)
      )
      assertTrue(TrendScreenQuality.eligible(stock))
    }
  )
}
