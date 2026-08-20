package io.greensheep.trading.core.types

final case class MarketId(country: String, provider: String) {
  def id: String = s"$country-$provider"
  def databaseName: String = s"ttk-$country-$provider"
  def job(name: String): String = s"$name:$id"
}

object MarketId {
  val UsMassive: MarketId = MarketId("us", "massive")
  val RoBvb: MarketId = MarketId("ro", "bvb")

  /** First segment is country; the rest is the provider (e.g. us-massive, ro-bvb). */
  def parse(raw: String): Either[String, MarketId] = {
    val trimmed = raw.trim.toLowerCase
    val dash = trimmed.indexOf('-')
    if (dash <= 0 || dash == trimmed.length - 1)
      Left(s"Market id must be country-provider, got '$raw'")
    else Right(MarketId(trimmed.substring(0, dash), trimmed.substring(dash + 1)))
  }

  def fromPath(country: String, provider: String): MarketId =
    MarketId(country.trim.toLowerCase, provider.trim.toLowerCase)
}
