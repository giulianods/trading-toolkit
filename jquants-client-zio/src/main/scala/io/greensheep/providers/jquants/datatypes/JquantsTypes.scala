package io.greensheep.providers.jquants.datatypes

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

final case class JquantsInstrument(
    ticker: String,
    code: String,
    name: String,
    segment: String
)

object JquantsInstrument {
  implicit val encoder: JsonEncoder[JquantsInstrument] = DeriveJsonEncoder.gen[JquantsInstrument]
  implicit val decoder: JsonDecoder[JquantsInstrument] = DeriveJsonDecoder.gen[JquantsInstrument]
}

final case class JquantsBar(
    date: String,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
    volume: Long
)

object JquantsBar {
  implicit val encoder: JsonEncoder[JquantsBar] = DeriveJsonEncoder.gen[JquantsBar]
  implicit val decoder: JsonDecoder[JquantsBar] = DeriveJsonDecoder.gen[JquantsBar]
}

final case class MasterRow(
    Date: Option[String],
    Code: String,
    CoName: Option[String],
    CoNameEn: Option[String],
    Mkt: Option[String],
    MktNm: Option[String],
    ProdCat: Option[String]
)

object MasterRow {
  implicit val decoder: JsonDecoder[MasterRow] = DeriveJsonDecoder.gen[MasterRow]
}

final case class MasterResponse(
    data: Option[Seq[MasterRow]],
    pagination_key: Option[String]
)

object MasterResponse {
  implicit val decoder: JsonDecoder[MasterResponse] = DeriveJsonDecoder.gen[MasterResponse]
}

final case class DailyBarRow(
    Date: String,
    Code: String,
    AdjO: Option[Double],
    AdjH: Option[Double],
    AdjL: Option[Double],
    AdjC: Option[Double],
    AdjVo: Option[Double],
    O: Option[Double],
    H: Option[Double],
    L: Option[Double],
    C: Option[Double],
    Vo: Option[Double]
)

object DailyBarRow {
  implicit val decoder: JsonDecoder[DailyBarRow] = DeriveJsonDecoder.gen[DailyBarRow]

  def toBar(row: DailyBarRow): Option[JquantsBar] = {
    val open = row.AdjO.orElse(row.O)
    val high = row.AdjH.orElse(row.H)
    val low = row.AdjL.orElse(row.L)
    val close = row.AdjC.orElse(row.C)
    val volume = row.AdjVo.orElse(row.Vo).map(_.toLong).getOrElse(0L)
    (open, high, low, close) match {
      case (Some(o), Some(h), Some(l), Some(c)) =>
        Some(JquantsBar(row.Date, o, h, l, c, math.max(0L, volume)))
      case _ => None
    }
  }
}

final case class DailyBarsResponse(
    data: Option[Seq[DailyBarRow]],
    pagination_key: Option[String]
)

object DailyBarsResponse {
  implicit val decoder: JsonDecoder[DailyBarsResponse] = DeriveJsonDecoder.gen[DailyBarsResponse]
}

final case class JquantsInstrumentInfo(
    ticker: String,
    name: String,
    description: String,
    code: String
)

object JquantsInstrumentInfo {
  implicit val encoder: JsonEncoder[JquantsInstrumentInfo] = DeriveJsonEncoder.gen[JquantsInstrumentInfo]
  implicit val decoder: JsonDecoder[JquantsInstrumentInfo] = DeriveJsonDecoder.gen[JquantsInstrumentInfo]
}
