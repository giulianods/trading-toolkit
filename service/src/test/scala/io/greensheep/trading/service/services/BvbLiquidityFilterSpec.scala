package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.Quote
import zio.test.*

import java.time.LocalDate

object BvbLiquidityFilterSpec extends ZIOSpecDefault {

  private def bar(day: LocalDate, volume: Int): Quote =
    Quote(day, o = 1, h = 1, l = 1, c = 1, v = volume)

  /** Dense weekday series: Mon–Fri for `weeks` weeks ending at `end`. */
  private def denseWeekdays(end: LocalDate, weeks: Int, volume: Int): Seq[Quote] = {
    val start = end.minusWeeks(weeks.toLong)
    Iterator
      .iterate(start)(_.plusDays(1))
      .takeWhile(!_.isAfter(end))
      .filter(d => d.getDayOfWeek.getValue <= 5)
      .map(d => bar(d, volume))
      .toSeq
  }

  override def spec = suite("BvbLiquidityFilter")(
    test("accepts dense series with meaningful recent volume") {
      val quotes = denseWeekdays(LocalDate.of(2026, 9, 18), weeks = 30, volume = 5_000)
      val a = BvbLiquidityFilter.assess(quotes)
      assertTrue(a.liquid, a.coverage >= 0.9, a.recentAvgVolume == 5000.0)
    },
    test("rejects sparse history even with some volume") {
      // ~one trade a month over five years → enough bars, terrible coverage
      val quotes = (0 until 70).map(i => bar(LocalDate.of(2020, 1, 15).plusMonths(i.toLong), volume = 10_000))
      val a = BvbLiquidityFilter.assess(quotes)
      assertTrue(!a.liquid, a.bars >= BvbLiquidityFilter.MinBars, a.reason.exists(_.contains("coverage")))
    },
    test("rejects dense but near-zero recent volume") {
      val quotes = denseWeekdays(LocalDate.of(2026, 9, 18), weeks = 30, volume = 10)
      val a = BvbLiquidityFilter.assess(quotes)
      assertTrue(!a.liquid, a.reason.exists(_.contains("avgVol")))
    },
    test("rejects series shorter than MinBars") {
      val quotes = denseWeekdays(LocalDate.of(2026, 9, 18), weeks = 4, volume = 5_000)
      val a = BvbLiquidityFilter.assess(quotes)
      assertTrue(!a.liquid, a.reason.exists(_.contains("bars=")))
    },
    test("HUNT-like dense AeRO name passes") {
      val quotes = denseWeekdays(LocalDate.of(2026, 9, 21), weeks = 100, volume = 20_000)
      assertTrue(BvbLiquidityFilter.isLiquid(quotes))
    }
  )
}
