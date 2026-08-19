package io.greensheep.providers.massive.datatypes.financials
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
