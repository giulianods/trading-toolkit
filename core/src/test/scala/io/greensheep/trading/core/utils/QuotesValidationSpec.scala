package io.greensheep.trading.core.utils

import io.greensheep.trading.core.types.{DailyQuoteData, Quote}
import zio.prelude.Validation
import zio.test.*

import java.time.LocalDate

object QuotesValidationSpec extends ZIOSpecDefault {

  private def quotes(days: LocalDate*): DailyQuoteData =
    DailyQuoteData(days.map(day => Quote(day, 1, 1, 1, 1, 1)))

  override def spec = suite("QuotesValidation")(
    test("accepts a weekend-sized gap") {
      val friday = LocalDate.of(2026, 8, 14)
      val monday = LocalDate.of(2026, 8, 17)
      assertTrue(QuotesValidation.validateAll(quotes(friday, monday)).isSuccess)
    },
    test("rejects a gap longer than 7 days") {
      val start = LocalDate.of(2026, 8, 3)
      val afterHole = start.plusDays(10)
      QuotesValidation.validateAll(quotes(start, afterHole)) match {
        case Validation.Failure(_, errors) =>
          assertTrue(errors.contains("There are missing dates"))
        case Validation.Success(_, _) =>
          assertTrue(false)
      }
    }
  )
}
