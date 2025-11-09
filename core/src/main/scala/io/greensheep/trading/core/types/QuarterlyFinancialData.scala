package io.greensheep.trading.core.types
import zio.json._
object QuarterlyFinancialData {
  implicit val Decoder: JsonDecoder[QuarterlyFinancialData] = DeriveJsonDecoder.gen[QuarterlyFinancialData]
  implicit val Encoder: JsonEncoder[QuarterlyFinancialData] = DeriveJsonEncoder.gen[QuarterlyFinancialData]

}
case class QuarterlyFinancialData(
                                 quarter: Int,
                                 year: Int,
                                 revenuePerShare: Double,
                                 basicEarningsPerShare: Double,
                                 netIncomeMargin: Double,
                                 operatingMargin: Double
                             ) {

  lazy val uuid: String = year.toString + "-" + quarter.toString


  override def toString: String = {
    s"$year | $quarter"
  }

}
