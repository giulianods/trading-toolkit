package io.greensheep.providers.xetra.datatypes

import zio.json.*
import zio.json.ast.Json

final case class XetraInstrument(
    ticker: String,
    isin: String,
    name: String,
    segment: String
)

object XetraInstrument {
  implicit val encoder: JsonEncoder[XetraInstrument] = DeriveJsonEncoder.gen[XetraInstrument]
  implicit val decoder: JsonDecoder[XetraInstrument] = DeriveJsonDecoder.gen[XetraInstrument]
}

final case class XetraBar(
    date: String,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
    volume: Long
)

object XetraBar {
  implicit val encoder: JsonEncoder[XetraBar] = DeriveJsonEncoder.gen[XetraBar]
  implicit val decoder: JsonDecoder[XetraBar] = DeriveJsonDecoder.gen[XetraBar]
}

final case class PriceHistoryRow(
    date: String,
    open: Option[Double],
    high: Option[Double],
    low: Option[Double],
    close: Option[Double],
    turnoverPieces: Option[Long],
    turnoverEuro: Option[Double] = None
)

object PriceHistoryRow {
  implicit val decoder: JsonDecoder[PriceHistoryRow] =
    JsonDecoder[Json.Obj].mapOrFail { obj =>
      val fields = obj.fields.toMap
      def field(name: String): Json = fields.getOrElse(name, Json.Null)
      val date = field("date") match {
        case Json.Str(value) => value
        case _               => ""
      }
      if (date.isEmpty) Left("missing date")
      else
        Right(
          PriceHistoryRow(
            date = date,
            open = readDouble(field("open")),
            high = readDouble(field("high")),
            low = readDouble(field("low")),
            close = readDouble(field("close")),
            turnoverPieces = readLong(field("turnoverPieces")),
            turnoverEuro = readDouble(field("turnoverEuro"))
          )
        )
    }

  private def readDouble(json: Json): Option[Double] =
    json match {
      case Json.Null                 => None
      case Json.Num(value)           => Some(value.doubleValue)
      case Json.Str(value)           => value.trim.toLowerCase match {
        case "" | "null" | "nan" | "infinity" | "-infinity" => None
        case other => scala.util.Try(other.toDouble).toOption
      }
      case _ => None
    }

  private def readLong(json: Json): Option[Long] =
    readDouble(json).map(_.toLong)

  def toBar(row: PriceHistoryRow): XetraBar =
    XetraBar(
      row.date,
      row.open.getOrElse(0.0),
      row.high.getOrElse(0.0),
      row.low.getOrElse(0.0),
      row.close.getOrElse(0.0),
      math.max(0L, row.turnoverPieces.getOrElse(0L))
    )
}

final case class PriceHistoryResponse(
    data: Option[Seq[PriceHistoryRow]],
    totalCount: Option[Int],
    isin: Option[String]
) {
  def bars: Seq[XetraBar] = data.getOrElse(Nil).map(PriceHistoryRow.toBar)
}

object PriceHistoryResponse {
  implicit val decoder: JsonDecoder[PriceHistoryResponse] =
    JsonDecoder[Json.Obj].mapOrFail { obj =>
      val fields = obj.fields.toMap
      val data = fields.get("data").flatMap {
        case Json.Arr(values) =>
          Some(values.flatMap {
            case row: Json.Obj => row.as[PriceHistoryRow].toOption
            case _             => None
          })
        case _ => Some(Nil)
      }
      val total = fields.get("totalCount").flatMap {
        case Json.Num(value) => Some(value.intValue)
        case _               => None
      }
      val isin = fields.get("isin").collect { case Json.Str(value) => value }
      Right(PriceHistoryResponse(data, total, isin))
    }
}

final case class XetraInstrumentInfo(
    ticker: String,
    name: String,
    description: String,
    isin: String,
    wkn: String
)

object XetraInstrumentInfo {
  implicit val encoder: JsonEncoder[XetraInstrumentInfo] = DeriveJsonEncoder.gen[XetraInstrumentInfo]
  implicit val decoder: JsonDecoder[XetraInstrumentInfo] = DeriveJsonDecoder.gen[XetraInstrumentInfo]
}
