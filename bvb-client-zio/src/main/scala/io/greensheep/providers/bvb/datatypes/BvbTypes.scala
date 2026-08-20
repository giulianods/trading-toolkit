package io.greensheep.providers.bvb.datatypes

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

final case class BvbInstrument(
    ticker: String,
    isin: String,
    name: String,
    category: String
)

object BvbInstrument {
  implicit val encoder: JsonEncoder[BvbInstrument] = DeriveJsonEncoder.gen[BvbInstrument]
  implicit val decoder: JsonDecoder[BvbInstrument] = DeriveJsonDecoder.gen[BvbInstrument]
}

final case class BvbBar(
    time: Long,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
    volume: Long
)

object BvbBar {
  implicit val encoder: JsonEncoder[BvbBar] = DeriveJsonEncoder.gen[BvbBar]
  implicit val decoder: JsonDecoder[BvbBar] = DeriveJsonDecoder.gen[BvbBar]
}

final case class BvbSymbolInfo(
    ticker: String,
    name: String,
    description: String,
    timezone: String,
    currencyCode: String
)

object BvbSymbolInfo {
  implicit val encoder: JsonEncoder[BvbSymbolInfo] = DeriveJsonEncoder.gen[BvbSymbolInfo]
  implicit val decoder: JsonDecoder[BvbSymbolInfo] = DeriveJsonDecoder.gen[BvbSymbolInfo]
}

final case class UdfHistory(
    s: String,
    t: Option[Seq[Long]] = None,
    o: Option[Seq[Double]] = None,
    h: Option[Seq[Double]] = None,
    l: Option[Seq[Double]] = None,
    c: Option[Seq[Double]] = None,
    v: Option[Seq[Double]] = None
) {
  def bars: Seq[BvbBar] =
    if (s == "no_data") Seq.empty
    else {
      val times = t.getOrElse(Seq.empty)
      val opens = o.getOrElse(Seq.fill(times.size)(0.0))
      val highs = h.getOrElse(opens)
      val lows = l.getOrElse(opens)
      val closes = c.getOrElse(opens)
      val volumes = v.getOrElse(Seq.fill(times.size)(0.0))
      times.indices.map { i =>
        BvbBar(
          time = times(i),
          open = opens.lift(i).getOrElse(0.0),
          high = highs.lift(i).getOrElse(0.0),
          low = lows.lift(i).getOrElse(0.0),
          close = closes.lift(i).getOrElse(0.0),
          volume = volumes.lift(i).getOrElse(0.0).toLong
        )
      }
    }
}

object UdfHistory {
  implicit val decoder: JsonDecoder[UdfHistory] = DeriveJsonDecoder.gen[UdfHistory]
  implicit val encoder: JsonEncoder[UdfHistory] = DeriveJsonEncoder.gen[UdfHistory]
}
