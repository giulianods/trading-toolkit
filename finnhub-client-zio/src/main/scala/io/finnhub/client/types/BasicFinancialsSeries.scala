package io.finnhub.client.types

import zio.json.*
import zio.json.ast.Json

object BasicFinancialsSeries {
  val empty: BasicFinancialsSeries =
    BasicFinancialsSeries(AnnualFinancialData(), QuarterlyFinancialData())

  implicit val decoder: JsonDecoder[BasicFinancialsSeries] =
    JsonDecoder[Json].mapOrFail {
      case Json.Null => Right(empty)
      case obj: Json.Obj =>
        def nested[A: JsonDecoder](name: String, fallback: A): Either[String, A] =
          obj.get(name) match {
            case None | Some(Json.Null) => Right(fallback)
            case Some(json)             => json.as[A]
          }

        for
          annual <- nested("annual", AnnualFinancialData())
          quarterly <- nested("quarterly", QuarterlyFinancialData())
        yield BasicFinancialsSeries(annual, quarterly)
      case other => Left(s"expected series object, got $other")
    }

  implicit val encoder: JsonEncoder[BasicFinancialsSeries] = DeriveJsonEncoder.gen[BasicFinancialsSeries]
}

final case class BasicFinancialsSeries(
    annual: AnnualFinancialData,
    quarterly: QuarterlyFinancialData
)
