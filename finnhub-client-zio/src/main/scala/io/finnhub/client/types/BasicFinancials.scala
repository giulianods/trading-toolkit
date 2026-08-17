package io.finnhub.client.types


import zio.json.*

object BasicFinancials {
    implicit val decoder: JsonDecoder[BasicFinancials] = DeriveJsonDecoder.gen[BasicFinancials]
    implicit val encoder: JsonEncoder[BasicFinancials] = DeriveJsonEncoder.gen[BasicFinancials]
}

final case class BasicFinancials(
    symbol: String,
    series: BasicFinancialsSeries
)
