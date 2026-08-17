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
    }
  )
}
