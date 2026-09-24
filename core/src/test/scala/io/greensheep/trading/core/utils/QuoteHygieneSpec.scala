package io.greensheep.trading.core.utils

import io.greensheep.trading.core.types.{DailyQuoteData, Quote}
import zio.test.*

import java.time.LocalDate

object QuoteHygieneSpec extends ZIOSpecDefault {

  private def q(day: LocalDate, close: Double): Quote =
    Quote(day, close, close, close, close, 1_000)

  override def spec = suite("QuoteHygiene")(
    test("truncates after a ticker-reuse sized gap") {
      val oldEnd = LocalDate.of(2025, 10, 9)
      val newStart = LocalDate.of(2026, 7, 17)
      val data = DailyQuoteData(
        Seq(
          q(oldEnd.minusDays(5), 5.0),
          q(oldEnd, 4.5),
          q(newStart, 1.2),
          q(newStart.plusDays(1), 1.3)
        )
      )
      assertTrue(
        QuoteHygiene.diagnose(data) == QuoteHygiene.TruncateAndRefetch(newStart),
        QuoteHygiene.lastSeriesStartAfterGap(data.quotes).contains(newStart)
      )
    },
    test("full reset on reverse-split sized jump without gap") {
      val d0 = LocalDate.of(2026, 8, 18)
      val data = DailyQuoteData(
        Seq(
          q(d0.minusDays(1), 0.45),
          q(d0, 6.8),
          q(d0.plusDays(1), 7.0)
        )
      )
      assertTrue(QuoteHygiene.diagnose(data) == QuoteHygiene.FullReset)
    },
    test("ok for a normal weekend gap") {
      val friday = LocalDate.of(2026, 8, 14)
      val monday = LocalDate.of(2026, 8, 17)
      val data = DailyQuoteData(Seq(q(friday, 10.0), q(monday, 10.2)))
      assertTrue(QuoteHygiene.diagnose(data) == QuoteHygiene.Ok)
    },
    test("ok when close moves less than jump ratio") {
      val d0 = LocalDate.of(2026, 8, 18)
      val data = DailyQuoteData(Seq(q(d0, 10.0), q(d0.plusDays(1), 25.0)))
      assertTrue(QuoteHygiene.diagnose(data) == QuoteHygiene.Ok)
    },
    test("full reset on zero-price junk bars") {
      val d0 = LocalDate.of(2026, 3, 17)
      val data = DailyQuoteData(
        Seq(
          Quote(d0, 0.0, 0.0, 0.0, 0.0, 100),
          q(d0.plusDays(1), 1.1)
        )
      )
      assertTrue(QuoteHygiene.diagnose(data) == QuoteHygiene.FullReset)
    }
  )
}
