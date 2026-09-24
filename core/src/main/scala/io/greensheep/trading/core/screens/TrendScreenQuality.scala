package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.Stock
import io.greensheep.trading.core.utils.QuoteHygiene

/** Shared quality gates so contaminated / thin series cannot dominate trend screens. */
object TrendScreenQuality {

  /** Need a full MA200 window; shorter series make stacked-MA SEPA meaningless. */
  val MinBars: Int = 200

  def eligible(stock: Stock): Boolean = {
    val quotes = stock.dailyAggregates.map(_.toQuote).toSeq
    stock.dailyAggregates.size >= MinBars &&
      stock.currentPrice > 0 &&
      stock.low52Week > 0 &&
      stock.high52Week > 0 &&
      stock.ma50.isFinite &&
      stock.ma150.isFinite &&
      stock.ma200.isFinite &&
      stock.perfQ1.isFinite &&
      stock.perfQ2.isFinite &&
      stock.perfQ3.isFinite &&
      stock.perfQ4.isFinite &&
      !QuoteHygiene.hasInvalidPrices(quotes) &&
      !QuoteHygiene.hasCloseJump(quotes) &&
      QuoteHygiene.lastSeriesStartAfterGap(quotes).isEmpty
  }
}
