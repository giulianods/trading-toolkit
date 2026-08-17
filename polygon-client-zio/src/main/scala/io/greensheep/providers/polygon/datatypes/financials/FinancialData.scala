package io.greensheep.providers.polygon.datatypes.financials

import zio.json._

object FinancialData {
  implicit val Decoder: JsonDecoder[FinancialData] =
    DeriveJsonDecoder.gen[FinancialData]
}

@jsonMemberNames(SnakeCase)
case class FinancialData(
                            id: String,
                            fiscalPeriod: String,
                            fiscalYear: String,
                            tickers: Seq[String],
                            financials: FinancialSnapshot

                            )
