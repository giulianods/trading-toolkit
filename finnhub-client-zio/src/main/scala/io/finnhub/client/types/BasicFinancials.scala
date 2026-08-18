package io.finnhub.client.types

import zio.json.*
import zio.json.ast.Json

object BasicFinancials {
  implicit val decoder: JsonDecoder[BasicFinancials] =
    JsonDecoder[Json.Obj].mapOrFail { obj =>
      for
        symbolJson <- obj.get("symbol").toRight("missing symbol")
        symbol <- symbolJson.as[String]
        series <- obj.get("series") match {
          case None | Some(Json.Null) => Right(BasicFinancialsSeries.empty)
          case Some(json)             => json.as[BasicFinancialsSeries]
        }
      yield BasicFinancials(symbol, series)
    }

  implicit val encoder: JsonEncoder[BasicFinancials] = DeriveJsonEncoder.gen[BasicFinancials]
}

final case class BasicFinancials(
    symbol: String,
    series: BasicFinancialsSeries
)
