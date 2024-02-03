package io.greensheep.trading.core.types

import zio.json.*

object AnnualFinancialData {
  implicit val Decoder: JsonDecoder[AnnualFinancialData] = DeriveJsonDecoder.gen[AnnualFinancialData]
  implicit val Encoder: JsonEncoder[AnnualFinancialData] = DeriveJsonEncoder.gen[AnnualFinancialData]

}

case class AnnualFinancialData(
                                ticker: String,
                                year: Int,
                                revenues: Double,
                                basicEarningsPerShare: Double,
                                dilutedEarningsPerShare: Double,
                                netCashFlow: Double,
                                netCashFlowFromOperatingActivities: Double,
                                netCashFlowFromInvestingActivities: Double,
                                netCashFlowFromFinancingActivities: Double
                              ) {

  lazy val uuid: String = year.toString


  override def toString: String = {
    s"$year | $revenues | $dilutedEarningsPerShare"
  }

}
