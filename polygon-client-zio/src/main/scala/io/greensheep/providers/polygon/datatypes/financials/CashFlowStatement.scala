package io.greensheep.providers.polygon.datatypes.financials

import zio.json.{DeriveJsonDecoder, JsonDecoder, SnakeCase, jsonMemberNames}

object CashFlowStatement {
  implicit def Decoder: JsonDecoder[CashFlowStatement] =
    DeriveJsonDecoder.gen[CashFlowStatement]
}

@jsonMemberNames(SnakeCase)
case class CashFlowStatement (
                             netCashFlow: Option[DataPointDouble],
                             netCashFlowFromOperatingActivities: Option[DataPointDouble],
                             netCashFlowFromInvestingActivities: Option[DataPointDouble],
                             netCashFlowFromFinancingActivities: Option[DataPointDouble]
                             ){

}
