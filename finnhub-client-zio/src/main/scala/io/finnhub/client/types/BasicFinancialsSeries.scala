package io.finnhub.client.types

import zio.json.*

object BasicFinancialsSeries {
    implicit val decoder: JsonDecoder[BasicFinancialsSeries] = DeriveJsonDecoder.gen[BasicFinancialsSeries]
    implicit val encoder: JsonEncoder[BasicFinancialsSeries] = DeriveJsonEncoder.gen[BasicFinancialsSeries]
}


final case class BasicFinancialsSeries(
    annual: AnnualFinancialData,
    quarterly: QuarterlyFinancialData
)
