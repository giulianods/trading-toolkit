package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.{Markets, Quote}
import io.greensheep.providers.massive.datatypes.Ticker
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate}

object AlpacaQuotesSpec extends ZIOSpecDefault {
  override def spec = suite("Alpaca quotes")(
    test("screens only classified common stock listed on NYSE or Nasdaq") {
      val assets = Seq(
        "IBM" -> "NYSE", "AAPL" -> "NASDAQ", "QQQ" -> "NASDAQ", "PREF" -> "NYSE",
        "OTC1" -> "OTC", "ARCA1" -> "ARCA", "UNKNOWN" -> "NASDAQ", "ADR" -> "NYSE"
      ).map { case (symbol, exchange) => AlpacaClient.Asset(symbol, symbol, exchange) }
      val reference = Seq(
        "IBM" -> "CS", "AAPL" -> "CS", "QQQ" -> "ETF", "PREF" -> "PS",
        "OTC1" -> "CS", "ARCA1" -> "CS", "ADR" -> "ADRC"
      ).map { case (symbol, kind) => Ticker(symbol, symbol, "", "stocks", "us", kind) }
      assertTrue(AlpacaQuotesServices.eligibleAssets(assets, reference).map(_.symbol) == Seq("IBM", "AAPL"))
    },
    test("registers a separate US market") {
      assertTrue(
        Markets.parseEnabled("us-massive,us-alpaca") == Right(Seq(Markets.UsMassive, Markets.UsAlpaca)),
        Markets.UsAlpaca.id.databaseName == "ttk-us-alpaca"
      )
    },
    test("requests the same session only after the New York evening cutoff") {
      val before = Instant.parse("2026-08-04T00:29:59Z")
      val after = Instant.parse("2026-08-04T00:30:00Z")
      val session = LocalDate.parse("2026-08-03")
      assertTrue(
        AlpacaClient.latestSessionCandidate(before) == session.minusDays(1),
        AlpacaClient.latestSessionCandidate(after) == session,
        AlpacaClient.barsEnd(session, after) == Instant.parse("2026-08-04T00:14:00Z"),
        AlpacaClient.latestSessionCandidate(Instant.parse("2026-01-07T01:30:00Z")) == LocalDate.parse("2026-01-06")
      )
    },
    test("decodes a paginated SIP bar and handles empty holiday responses") {
      val page = """{"bars":{"AAPL":[{"t":"2026-08-03T04:00:00Z","o":10,"h":12,"l":9,"c":11,"v":200}]},"next_page_token":"next"}"""
        .fromJson[AlpacaClient.BarsPage]
      val empty = """{"bars":null,"next_page_token":null}""".fromJson[AlpacaClient.BarsPage]
      assertTrue(
        page.exists(_.next.contains("next")),
        page.exists(_.bars.exists(_("AAPL").head.quote.day == LocalDate.parse("2026-08-03"))),
        empty.exists(_.bars.isEmpty)
      )
    },
    test("detects repriced overlap for split history repair") {
      val quote = Quote(LocalDate.parse("2026-08-03"), 10, 12, 9, 11, 200)
      assertTrue(
        AlpacaQuotesServices.adjustmentChanged(Seq(quote), Seq(quote.copy(c = 5.5))),
        !AlpacaQuotesServices.adjustmentChanged(Seq(quote), Seq(quote.copy(v = 205))),
        !AlpacaQuotesServices.adjustmentChanged(Seq(quote), Seq(quote.copy(day = quote.day.plusDays(1))))
      )
    }
  )
}
