package io.finnhub.client.types

import zio.json.*
import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import zio.json.internal.RetractReader

object FinancialDataPointDouble {
    implicit val decoder: JsonDecoder[FinancialDataPointDouble] = DeriveJsonDecoder.gen[FinancialDataPointDouble]
        .orElse {
            // custom decoder to handle null values for "v"
            import zio.json.ast.Json
            import zio.json.{JsonDecoder, JsonEncoder, DeriveJsonEncoder, JsonFieldDecoder}

            new JsonDecoder[FinancialDataPointDouble] {
                override def unsafeDecode(trace: List[JsonError], in: RetractReader): FinancialDataPointDouble = {
                    val json = JsonDecoder[Json].unsafeDecode(trace, in)
                    val obj = json.asObject.getOrElse(
                        throw new RuntimeException("Expected JSON object for FinancialDataPointDouble")
                    )
                    val period = obj.get("period").flatMap(_.asString).getOrElse(
                        throw new RuntimeException("Missing required field: period")
                    )
                    val vValue: Double = obj.get("v") match {
                        case None          => Double.NaN
                        case Some(Json.Null) => Double.NaN
                        case Some(Json.Num(str)) => str.toDouble
                        case Some(Json.Str(str)) => str.toDouble
                        case Some(other)   => throw new RuntimeException(s"Expected number for 'v', got $other")
                    }
                    FinancialDataPointDouble(period, vValue)
                }
            }
        }
    implicit val encoder: JsonEncoder[FinancialDataPointDouble] = DeriveJsonEncoder.gen[FinancialDataPointDouble]
}

final case class FinancialDataPointDouble(
    period: String,
    v: Double
)

