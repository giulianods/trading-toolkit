package io.greensheep.trading.service

import io.greensheep.trading.core.types.DailyQuoteData
import zio.prelude._

package object services {


  def sanityCheckQuotes(quotes: DailyQuoteData): Boolean = {
    // check if the records are in chronological order
    val dates = quotes.quotes.map(_.day)
    val isChronological = dates == dates.sorted
    // check if there are no duplicate dates
    val noDuplicates = dates.distinct.size == dates.size
    // check if there are no missing dates (weekends and holidays are allowed)
    val noMissingDates = dates.sliding(2).forall {
      case Seq(d1, d2) => !d1.plusDays(7).isBefore(d2)
      case _ => true
    }
    isChronological && noDuplicates && noMissingDates
  }

}
