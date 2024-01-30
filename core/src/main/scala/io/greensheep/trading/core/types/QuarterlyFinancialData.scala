package io.greensheep.trading.core.types
import zio.json._
object QuarterlyFinancialData {
  implicit val Decoder: JsonDecoder[QuarterlyFinancialData] = DeriveJsonDecoder.gen[QuarterlyFinancialData]
  implicit val Encoder: JsonEncoder[QuarterlyFinancialData] = DeriveJsonEncoder.gen[QuarterlyFinancialData]

}
case class QuarterlyFinancialData(
                                 quarter: String,
                                 year: Int,
                                 revenues: Double,
                                 basicEarningsPerShare: Double,
                                 dilutedEarningsPerShare: Double,
                                 netCashFlow: Double,
                                 netCashFlowFromOperatingActivities: Double,
                                 netCashFlowFromInvestingActivities: Double,
                                 netCashFlowFromFinancingActivities: Double
                             ) {

  lazy val uuid: String = year.toString + "-" + quarter


  override def toString: String = {
    s"$year | $quarter | $revenues | $dilutedEarningsPerShare"
  }

}
