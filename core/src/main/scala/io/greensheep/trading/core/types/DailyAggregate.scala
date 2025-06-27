package io.greensheep.trading.core.types

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.LocalDate

object DailyAggregate {

  implicit val Encoder: JsonEncoder[DailyAggregate] = DeriveJsonEncoder.gen[DailyAggregate]

  implicit val Decoder: JsonDecoder[DailyAggregate] = DeriveJsonDecoder.gen[DailyAggregate]

  def apply(quote: Quote): DailyAggregate = {
    DailyAggregate(
      o = quote.o,
      h = quote.h,
      l = quote.l,
      c = quote.c,
      v = quote.v.toDouble,
      t = quote.day
    )
  }
}

case class DailyAggregate(
                         o: Double,
                         h: Double,
                         l: Double,
                         c: Double,
                         v: Double,
                         t: LocalDate
                    ) {

  def toQuote: Quote = Quote(t, o, h, l, c, v.toInt)
}