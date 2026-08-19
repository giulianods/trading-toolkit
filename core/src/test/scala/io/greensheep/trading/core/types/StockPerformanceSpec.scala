package io.greensheep.trading.core.types

import zio.Chunk
import zio.test.*

import java.time.LocalDate

object StockPerformanceSpec extends ZIOSpecDefault {

  private def stockWithCloses(closes: (LocalDate, Double)*): Stock =
    Stock(
      symbol = "TEST",
      companyName = "Test",
      ipoDate = closes.head._1,
      dailyAggregates = Chunk.fromIterable(closes.map { case (day, close) =>
        DailyAggregate(o = close, h = close, l = close, c = close, v = 1, t = day)
      })
    )

  override def spec = suite("Stock.performanceDays")(
    test("100 to 107 is a 7 percent gain") {
      val start = LocalDate.of(2026, 8, 17)
      val stock = stockWithCloses(start -> 100.0, start.plusDays(1) -> 107.0)
      assertTrue(stock.performanceDays(2) == 7.0)
    },
    test("100 to 93 is a 7 percent loss") {
      val start = LocalDate.of(2026, 8, 17)
      val stock = stockWithCloses(start -> 100.0, start.plusDays(1) -> 93.0)
      assertTrue(stock.performanceDays(2) == -7.0)
    }
  )
}
