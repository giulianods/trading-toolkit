package io.greensheep.trading.core.types

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

object ScreenStats {
  
   implicit val Encoder: JsonEncoder[ScreenStats] = DeriveJsonEncoder.gen[ScreenStats]

   implicit val Decoder: JsonDecoder[ScreenStats] = DeriveJsonDecoder.gen[ScreenStats]
}

case class ScreenStats(ticker: String,
                       stats: Map[String, String])
