package io.greensheep.trading.core.utils

import io.greensheep.trading.core.types.DailyQuoteData
import zio.{NonEmptyChunk, ZIO}
import zio.prelude.*
import zio.prelude.classic.CommutativeSemigroup

object QuotesValidation {

  private def validateChronological(quotes: DailyQuoteData): Validation[String, DailyQuoteData] = {
    val dates = quotes.quotes.map(_.day)
    if (dates == dates.sorted) Validation.succeed(quotes)
    else Validation.fail("Quotes are not in chronological order")
  }

  private def validateNoDuplicates(quotes: DailyQuoteData): Validation[String, DailyQuoteData] = {
    val dates = quotes.quotes.map(_.day.toString)
    if (dates.toSet.size == dates.size) Validation.succeed(quotes)
    else Validation.fail("There are duplicate dates")
  }

  private def validateNoMissingDates(quotes: DailyQuoteData): Validation[String, DailyQuoteData] = {
    val dates = quotes.quotes.map(_.day)
    val noMissingDates = dates.sliding(2).forall {
      case Seq(d1, d2) => !d1.plusDays(7).isBefore(d2)
      case _ => true
    }
    if (noMissingDates) Validation.succeed(quotes)
    else Validation.fail("There are missing dates")
  }

  def validateAll(quotes: DailyQuoteData): Validation[String, DailyQuoteData] = {
    Validation.validateWith(
      validateChronological(quotes),
      validateNoDuplicates(quotes),
      validateNoMissingDates(quotes)
    ) { case _ => quotes }
  }
}
