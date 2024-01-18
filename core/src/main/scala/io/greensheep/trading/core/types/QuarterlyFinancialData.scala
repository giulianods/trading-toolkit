package io.greensheep.trading.core.types

case class QuarterlyFinancialData(
                                 quarter: String,
                                 year: Int,
                                 revenues: Double,
                                 grossProfit: Double,
                                 basicEarningsPerShare: Double,
                                 dilutedEarningsPerShare: Double
                             ) {


  override def toString: String = {
    s"$year | $quarter | $revenues | $dilutedEarningsPerShare"
  }

}
