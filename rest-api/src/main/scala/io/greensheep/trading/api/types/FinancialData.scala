package io.greensheep.trading.api.types

import io.greensheep.trading.core.types.QuarterlyFinancialData
import zio.json.*

object FinancialData {

  implicit val Encoder: JsonEncoder[FinancialData] = DeriveJsonEncoder.gen[FinancialData]

  implicit val Decoder: JsonDecoder[FinancialData] = DeriveJsonDecoder.gen[FinancialData]
}

case class FinancialData(
                        ticker: String,
                        quarterly: Seq[QuarterlyFinancialData]
                        ) {

}
