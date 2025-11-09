package io.greensheep.trading.core.types

import zio.json.*

object AnnualFinancialData {
  implicit val Decoder: JsonDecoder[AnnualFinancialData] = DeriveJsonDecoder.gen[AnnualFinancialData]
  implicit val Encoder: JsonEncoder[AnnualFinancialData] = DeriveJsonEncoder.gen[AnnualFinancialData]

}

case class AnnualFinancialData(
                                year: Int,
                                revenuePerShare: Double,
                                basicEarningsPerShare: Double,
                                operatingMargin: Double,
                                netIncomeMargin: Double,
                              ) {

  lazy val uuid: String = year.toString


  override def toString: String = {
    s"$year"
  }

}
