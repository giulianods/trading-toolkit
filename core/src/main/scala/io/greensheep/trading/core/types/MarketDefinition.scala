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
    hasFinancials: Boolean,
    providers: Option[MarketSources] = None,
    displayLabel: Option[String] = None,
    aliases: Seq[MarketId] = Seq.empty
) {
  def sources: MarketSources = providers.getOrElse(MarketSources.defaults(id, dataSource))
  def label: String = displayLabel.getOrElse {
    val price = MarketSources.name(sources.prices)
    val financials = if (sources.financials == "none") "" else s" + ${MarketSources.name(sources.financials)}"
    s"${id.country.toUpperCase(java.util.Locale.ROOT)} · $price$financials"
  }
}

final case class MarketSources(prices: String, financials: String, metadata: String, listings: String)

object MarketSources {
  def name(value: String): String = Map("massive" -> "Massive", "alpaca" -> "Alpaca", "finnhub" -> "Finnhub",
    "eodhd" -> "EODHD", "bvb" -> "BVB", "xetra" -> "Xetra", "jquants" -> "J-Quants", "none" -> "None")
    .getOrElse(value, value)

  def defaults(id: MarketId, source: String): MarketSources = {
    val prices = if (source == "finnhub") "alpaca" else source
    MarketSources(prices, if (id.country == "us") "finnhub" else if (id.country == "ro") "eodhd" else "none",
      if (id.country == "us") "massive" else prices,
      if (prices == "alpaca") "finnhub" else prices)
  }

  def validate(country: String, sources: MarketSources): Either[String, Unit] = {
    val priceCountries = Map("massive" -> "us", "alpaca" -> "us", "bvb" -> "ro", "xetra" -> "de", "jquants" -> "jp", "eodhd" -> "ca")
    if (!priceCountries.get(sources.prices).contains(country)) Left(s"Price provider '${sources.prices}' does not support '$country'")
    else if (!(Set("none", "eodhd").contains(sources.financials) || sources.financials == "finnhub" && country == "us"))
      Left(s"Unsupported financials provider '${sources.financials}' for '$country'")
    else if (sources.financials == "eodhd" && !Set("us", "ro").contains(country)) Left("EODHD income statements are supported for US and RO profiles")
    else if (!(sources.metadata == "none" || sources.metadata == sources.prices || sources.metadata == "massive" && country == "us"))
      Left(s"Unsupported metadata provider '${sources.metadata}' for '$country'")
    else if (sources.prices == "alpaca" && !Set("massive", "finnhub").contains(sources.listings))
      Left("Alpaca common-stock listings must come from massive or finnhub")
    else if (sources.prices != "alpaca" && sources.listings != sources.prices)
      Left(s"Listings for '${sources.prices}' must use its native provider")
    else Right(())
  }
}
