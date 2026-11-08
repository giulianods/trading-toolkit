package io.greensheep.trading.core.types

import zio.json.*

import java.time.LocalDate

final case class IncomePeriod(
    date: LocalDate,
    currency: Option[String],
    revenue: Option[Double],
    netIncome: Option[Double],
    netMarginPct: Option[Double],
    eps: Option[Double]
)

object IncomePeriod {
  given JsonCodec[IncomePeriod] = DeriveJsonCodec.gen[IncomePeriod]
}

final case class IncomeStatements(annual: Seq[IncomePeriod], quarterly: Seq[IncomePeriod])

object IncomeStatements {
  val empty: IncomeStatements = IncomeStatements(Seq.empty, Seq.empty)
  given JsonCodec[IncomeStatements] = DeriveJsonCodec.gen[IncomeStatements]
}
