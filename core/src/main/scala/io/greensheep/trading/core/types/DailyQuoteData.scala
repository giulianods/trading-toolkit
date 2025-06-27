package io.greensheep.trading.core.types

object DailyQuoteData {
  implicit val Encoder: zio.json.JsonEncoder[DailyQuoteData] = zio.json.DeriveJsonEncoder.gen[DailyQuoteData]
  implicit val Decoder: zio.json.JsonDecoder[DailyQuoteData] = zio.json.DeriveJsonDecoder.gen[DailyQuoteData]
}

case class DailyQuoteData(
                         quotes: Seq[Quote]
                         )
