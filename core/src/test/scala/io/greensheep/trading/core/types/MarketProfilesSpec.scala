package io.greensheep.trading.core.types

import zio.test.*

object MarketProfilesSpec extends ZIOSpecDefault {
  private def configure(ids: Seq[String], values: Map[String, String] = Map.empty, lists: Map[String, Seq[String]] = Map.empty) =
    Markets.configured(ids, values.get, lists.get)

  override def spec = suite("Configured provider profiles")(
    test("composes price and financial providers independently and allows custom profile IDs") {
      val profiles = configure(Seq("us-massive", "us-custom"), Map(
        "ttk.profiles.us-custom.prices" -> "alpaca", "ttk.profiles.us-custom.financials" -> "finnhub",
        "ttk.profiles.us-custom.metadata" -> "massive"))
      assertTrue(profiles.exists(_.map(_.sources.prices) == Seq("massive", "alpaca")),
        profiles.exists(_.forall(_.sources.financials == "finnhub")),
        profiles.exists(_.last.id.databaseName == "ttk-us-custom"),
        profiles.exists(_.last.cron == Markets.UsAlpaca.cron))
    },
    test("changing financial providers preserves profile identity and price schedule") {
      val result = configure(Seq("us-alpaca"), Map("ttk.profiles.us-alpaca.financials" -> "none"))
      assertTrue(result.exists(_.head.id == MarketId.UsAlpaca), result.exists(_.head.sources.prices == "alpaca"),
        result.exists(_.head.sources.financials == "none"))
    },
    test("rejects unsupported capability/country combinations and ambiguous aliases") {
      assertTrue(
        configure(Seq("ro-bvb"), Map("ttk.profiles.ro-bvb.financials" -> "finnhub")).isLeft,
        configure(Seq("us-alpaca"), Map("ttk.profiles.us-alpaca.listings" -> "alpaca")).isLeft,
        configure(Seq("us-alpaca", "us-finnhub"), lists = Map("ttk.profiles.us-alpaca.aliases" -> Seq("us-finnhub"))).isLeft,
        configure(Seq("us-alpaca"), lists = Map("ttk.profiles.us-alpaca.aliases" -> Seq("ro-bvb"))).isLeft
      )
    },
    test("keeps native regional providers while configuring optional financials explicitly") {
      val result = configure(Seq("ro-bvb", "de-xetra", "jp-jquants", "ca-eodhd"))
      assertTrue(result.exists(_.map(_.sources.prices) == Seq("bvb", "xetra", "jquants", "eodhd")),
        result.exists(_.map(_.sources.financials) == Seq("eodhd", "none", "none", "none")))
    }
  )
}
