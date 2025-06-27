package io.greensheep.trading.core.types

import java.time.LocalDate

object Quote {
  implicit val Encoder: zio.json.JsonEncoder[Quote] = zio.json.DeriveJsonEncoder.gen[Quote]
  implicit val Decoder: zio.json.JsonDecoder[Quote] = zio.json.DeriveJsonDecoder.gen[Quote]
}
case class Quote(day:LocalDate, o: Double, h: Double, l: Double, c: Double, v: Int)
