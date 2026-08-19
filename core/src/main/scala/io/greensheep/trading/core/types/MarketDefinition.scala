package io.greensheep.trading.core.types

final case class MarketCron(
    quotes: String,
    splits: String,
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
