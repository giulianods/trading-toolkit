package io.greensheep.trading.service.services

import zio.test.*

import java.time.LocalDate

object QuotesWindowSpec extends ZIOSpecDefault {

  override def spec = suite("quote fetch window")(
    test("includes yesterday and excludes today") {
      val today = LocalDate.now()
      val yesterday = today.minusDays(1)
      val window = QuotesServices.datesBetweenInclusive(yesterday.minusDays(2), today)

      assertTrue(
        window.contains(yesterday),
        !window.contains(today),
        window.lastOption.contains(yesterday)
      )
    },
    test("returns a single day when start and end are yesterday") {
      val yesterday = LocalDate.now().minusDays(1)
      assertTrue(QuotesServices.datesBetweenInclusive(yesterday, yesterday) == Seq(yesterday))
    },
    test("returns empty when end is before start") {
      val today = LocalDate.now()
      assertTrue(QuotesServices.datesBetweenInclusive(today, today.minusDays(2)).isEmpty)
    },
    test("empty grouped-daily does not advance lastOk") {
      val friday = LocalDate.of(2026, 8, 14)
      val saturday = friday.plusDays(1)
      val (lastOk, firstFailure) =
        QuotesServices.stepQuoteCursor(Some(friday), None, saturday, Right(false))
      assertTrue(lastOk.contains(friday), firstFailure.isEmpty)
    },
    test("a written session advances lastOk") {
      val friday = LocalDate.of(2026, 8, 14)
      val monday = friday.plusDays(3)
      val (lastOk, firstFailure) =
        QuotesServices.stepQuoteCursor(Some(friday), None, monday, Right(true))
      assertTrue(lastOk.contains(monday), firstFailure.isEmpty)
    },
    test("a fetch error freezes the cursor and later days do not advance it") {
      val friday = LocalDate.of(2026, 8, 14)
      val saturday = friday.plusDays(1)
      val sunday = friday.plusDays(2)
      val failed = QuotesServices.stepQuoteCursor(
        Some(friday),
        None,
        saturday,
        Left(new RuntimeException("timeout"))
      )
      val after = QuotesServices.stepQuoteCursor(failed._1, failed._2, sunday, Right(true))
      assertTrue(
        failed._1.contains(friday),
        failed._2.contains(saturday),
        after._1.contains(friday),
        after._2.contains(saturday)
      )
    }
  )
}
