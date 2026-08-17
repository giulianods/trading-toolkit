package io.finnhub.client.types

import zio.json.*

object FinancialDataPointInt {
    implicit val decoder: JsonDecoder[FinancialDataPointInt] = DeriveJsonDecoder.gen[FinancialDataPointInt]
    implicit val encoder: JsonEncoder[FinancialDataPointInt] = DeriveJsonEncoder.gen[FinancialDataPointInt]
}

final case class FinancialDataPointInt(
    period: String,
    v: Int
)
