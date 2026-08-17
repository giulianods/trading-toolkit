package io.greensheep.providers.polygon.datatypes.financials
import zio.json.*

object FinancialSnapshot{
  implicit def Decoder: JsonDecoder[FinancialSnapshot] =
    DeriveJsonDecoder.gen[FinancialSnapshot]
}

@jsonMemberNames(SnakeCase)
case class FinancialSnapshot(
                            cashFlowStatement: Option[CashFlowStatement],
                            incomeStatement: Option[IncomeStatement]
                            )
