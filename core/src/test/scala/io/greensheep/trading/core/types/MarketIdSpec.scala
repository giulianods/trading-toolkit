package io.greensheep.trading.core.types

import zio.test.*

object MarketIdSpec extends ZIOSpecDefault {

  override def spec = suite("MarketId")(
    test("US Massive database is ttk-us-massive") {
      assertTrue(MarketId.UsMassive.databaseName == "ttk-us-massive")
    },
    test("Romania BVB database is ttk-ro-bvb") {
      assertTrue(MarketId("ro", "bvb").databaseName == "ttk-ro-bvb")
    },
    test("job names include the market id") {
      assertTrue(
        MarketId.UsMassive.job("quotes") == "quotes:us-massive",
        MarketId.UsMassive.job("screens") == "screens:us-massive",
        MarketId("ro", "bvb").job("quotes") == "quotes:ro-bvb"
      )
    },
    test("parses country-provider tokens") {
      assertTrue(
        MarketId.parse("us-massive") == Right(MarketId.UsMassive),
        MarketId.parse("RO-BVB") == Right(MarketId("ro", "bvb"))
      )
    },
    test("rejects malformed market ids") {
      assertTrue(
        MarketId.parse("us").isLeft,
        MarketId.parse("-massive").isLeft,
        MarketId.parse("us-").isLeft
      )
    },
    test("enabled markets default to us-massive") {
      assertTrue(Markets.parseEnabled("us-massive") == Right(Seq(Markets.UsMassive)))
    },
    test("ro-bvb is a registered catalog market") {
      assertTrue(Markets.parseEnabled("ro-bvb") == Right(Seq(Markets.RoBvb)))
    },
    test("unknown markets are rejected") {
      assertTrue(Markets.parseEnabled("us-other").isLeft)
    }
  )
}
