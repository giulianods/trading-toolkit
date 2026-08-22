package io.greensheep.providers.jquants

import io.greensheep.providers.jquants.datatypes.{DailyBarsResponse, MasterResponse, MasterRow}
import zio.json.*
import zio.test.*

object JquantsModelSpec extends ZIOSpecDefault {

  override def spec = suite("J-Quants client models")(
    test("tickerFromCode strips trailing common-stock zero") {
      assertTrue(
        JquantsUniverse.tickerFromCode("86970") == "8697",
        JquantsUniverse.tickerFromCode("7203") == "7203"
      )
    },
    test("filters Prime/Standard common shares and drops Growth/ETFs") {
      val prime = MasterRow(None, "86970", Some("JPX"), Some("Japan Exchange Group,Inc."), Some("0111"), Some("プライム"), Some("011"))
      val growth = MasterRow(None, "12340", Some("Growth Co"), None, Some("0113"), Some("グロース"), Some("011"))
      val etf = MasterRow(None, "13060", Some("ETF"), None, Some("0111"), Some("プライム"), Some("010"))
      val instruments = Seq(prime, growth, etf).filter(JquantsUniverse.isMainBoardShare).map(JquantsUniverse.toInstrument)
      assertTrue(instruments.map(_.ticker) == Seq("8697"), instruments.head.segment == "0111")
    },
    test("decodes daily bars and prefers adjusted OHLC") {
      val json =
        """{"data":[{"Date":"2023-03-24","Code":"86970","O":100.0,"H":110.0,"L":90.0,"C":105.0,"Vo":1000.0,"AdjO":50.0,"AdjH":55.0,"AdjL":45.0,"AdjC":52.5,"AdjVo":2000.0}]}"""
      val bars = json.fromJson[DailyBarsResponse].toOption.toSeq.flatMap(_.data.getOrElse(Nil)).flatMap(io.greensheep.providers.jquants.datatypes.DailyBarRow.toBar)
      assertTrue(bars.head.close == 52.5, bars.head.volume == 2000)
    },
    test("empty master data is ok") {
      val decoded = """{"data":[]}""".fromJson[MasterResponse]
      assertTrue(decoded.toOption.exists(_.data.exists(_.isEmpty)))
    },
    test("probe 2xx speeds up and 429 without headers slows and retries") {
      val start = JquantsRateLimitState.probe(2000)
      val ok = JquantsRateLimit.step(start, ProbeEvent.Success)
      val limited = JquantsRateLimit.step(ok.state, ProbeEvent.TooManyRequests(RateLimitHeaders(None, None, None, None, Map.empty)))
      assertTrue(
        ok.waitMs <= start.intervalMs,
        !ok.retrySame,
        limited.retrySame,
        limited.state.intervalMs >= 5000,
        limited.waitMs >= 12000
      )
    }
  )
}
