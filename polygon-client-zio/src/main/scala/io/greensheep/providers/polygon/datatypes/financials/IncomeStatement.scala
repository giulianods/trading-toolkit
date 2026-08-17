package io.greensheep.providers.polygon.datatypes.financials

import zio.json._

object IncomeStatement {
  implicit val Decoder: JsonDecoder[IncomeStatement] =
    DeriveJsonDecoder.gen[IncomeStatement]
}

@jsonMemberNames(SnakeCase)
case class IncomeStatement(
                            //TODO check how to handle precision loss
                            revenues: Option[DataPointDouble],
                            netIncomeLoss: Option[DataPointDouble],
                            basicEarningsPerShare: Option[DataPointDouble],
                            dilutedEarningsPerShare: Option[DataPointDouble]
                          )
