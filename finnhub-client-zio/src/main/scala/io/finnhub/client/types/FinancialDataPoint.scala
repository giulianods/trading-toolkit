package io.finnhub.client.types

import scala.math.BigDecimal
import zio.json.*

object FinancialDataPoint {
    implicit val decoder: JsonDecoder[FinancialDataPoint] =
      JsonDecoder[Map[String, zio.json.ast.Json]].map { fields =>
        val period = fields.get("period").flatMap(_.asString).getOrElse("")
        val vValue: Option[BigDecimal] = fields.get("v") match {
          case None | Some(zio.json.ast.Json.Null) => None
          case Some(zio.json.ast.Json.Num(str))    => Some(BigDecimal(str))
          case Some(zio.json.ast.Json.Str(str))    => scala.util.Try(BigDecimal(str)).toOption
          case Some(_)                             => None
        }
        FinancialDataPoint(period, vValue)
      }
    implicit val encoder: JsonEncoder[FinancialDataPoint] = DeriveJsonEncoder.gen[FinancialDataPoint]
}

final case class FinancialDataPoint(
    period: String,
    v: Option[BigDecimal]
)
