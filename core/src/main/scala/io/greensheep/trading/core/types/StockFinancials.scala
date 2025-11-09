package io.greensheep.trading.core.types

import zio.json.*

object StockFinancials {
  implicit val Decoder: JsonDecoder[StockFinancials] = DeriveJsonDecoder.gen[StockFinancials]
  implicit val Encoder: JsonEncoder[StockFinancials] = DeriveJsonEncoder.gen[StockFinancials]
}

final case class StockFinancials(
    ticker: String,
    annual: Seq[AnnualFinancialData],
    quarterly: Seq[QuarterlyFinancialData]
)
