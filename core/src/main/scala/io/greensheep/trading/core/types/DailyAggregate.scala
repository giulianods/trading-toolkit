package io.greensheep.trading.core.types

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.LocalDate

object DailyAggregate {

  implicit val Encoder: JsonEncoder[DailyAggregate] = DeriveJsonEncoder.gen[DailyAggregate]

  implicit val Decoder: JsonDecoder[DailyAggregate] = DeriveJsonDecoder.gen[DailyAggregate]
}

case class DailyAggregate(
                         o: Double,
                         h: Double,
                         l: Double,
                         c: Double,
                         v: Double,
                         t: LocalDate
                    )
