package io.finnhub.client.types

import zio.json.*

object StockSymbol {
    implicit val decoder: JsonDecoder[StockSymbol] = DeriveJsonDecoder.gen[StockSymbol]
    implicit val encoder: JsonEncoder[StockSymbol] = DeriveJsonEncoder.gen[StockSymbol]
}

final case class StockSymbol(
    currency: Option[String] = None,
    description: String,
    displaySymbol: String,
    figi: Option[String] = None,
    isin: Option[String] = None,
    mic: String,
    shareClassFIGI: Option[String] = None,
    symbol: String,
    symbol2: Option[String] = None,
    `type`: String
)
