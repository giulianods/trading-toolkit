package io.greensheep.trading.core.types

final case class MarketCron(
    quotes: String,
    splits: String,
    /** Optional US quote hygiene (ticker reuse / unadjusted jumps). */
    clean: Option[String],
    screens: String,
    financials: Option[String]
)

final case class MarketDefinition(
    id: MarketId,
    timeZone: String,
    dataSource: String,
    cron: MarketCron,
    hasFinancials: Boolean
)
