package io.greensheep.providers.bvb

import io.greensheep.providers.bvb.datatypes.UdfHistory
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate}

object BvbModelSpec extends ZIOSpecDefault {

  override def spec = suite("BVB client models")(
    test("history URL includes 1D resolution, ajust and RON") {
      val url = BvbHistoryRequest.buildUrl("TLV", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
      assertTrue(
        url.startsWith("https://wapi.bvb.ro/api/history?"),
        url.contains("symbol=TLV"),
        url.contains("rs=1D"),
        url.contains("ajust=1"),
        url.contains("currencyCode=RON"),
        url.contains("countback=")
      )
    },
    test("decodes UDF history into bars") {
      val json =
        """{"s":"ok","t":[1704067200],"o":[1.0],"h":[1.5],"l":[0.9],"c":[1.2],"v":[1000]}"""
      val decoded = json.fromJson[UdfHistory]
      assertTrue(
        decoded.exists(_.s == "ok"),
        decoded.toOption.exists(_.bars.head.close == 1.2),
        decoded.toOption.exists(_.bars.head.volume == 1000)
      )
    },
    test("no_data is an empty series") {
      val decoded = """{"s":"no_data"}""".fromJson[UdfHistory]
      assertTrue(decoded.toOption.exists(_.bars.isEmpty))
    },
    test("shares table HTML yields TLV SNP H2O") {
      val html =
        """<table>
          |<tr><td><a href="Details.aspx?s=TLV">TLV</a> ROTLVAACNOR1</td><td>BANCA TRANSILVANIA S.A.</td><td>Premium</td></tr>
          |<tr><td><a href="Details.aspx?s=SNP">SNP</a> ROSNPPACNOR9</td><td>OMV PETROM S.A.</td><td>Premium</td></tr>
          |<tr><td><a href="Details.aspx?s=H2O">H2O</a> RO4Q0Z5RO1B6</td><td>HIDROELECTRICA</td><td>Premium</td></tr>
          |</table>""".stripMargin
      val tickers = SharesPageParser.parse(html).map(_.ticker)
      assertTrue(tickers.contains("TLV"), tickers.contains("SNP"), tickers.contains("H2O"))
    },
    test("AeRO table fragment includes HUNT with ISIN") {
      val html =
        """<tr>
          |  <td><a href="/FinancialInstruments/Details/FinancialInstrumentsDetails.aspx?s=HUNT"><b>HUNT</b></a>
          |  <p style="font-size: 11px">RODHRTKI5XP6</p></td>
          |  <td>IHUNT TECHNOLOGY IMPORT-EXPORT S.A.</td>
          |  <td align="center">AeRO Premium</td>
          |</tr>""".stripMargin
      val instruments = SharesPageParser.parse(html)
      assertTrue(
        instruments.exists(i => i.ticker == "HUNT" && i.isin == "RODHRTKI5XP6"),
        instruments.head.category.contains("AeRO")
      )
    },
    test("AeRO ASP.NET post fields target the ms3 update panel") {
      val html =
        """<input type="hidden" name="__VIEWSTATE" value="abc" />
          |<input type="hidden" name="__VIEWSTATEGENERATOR" value="gen" />
          |<input type="hidden" name="__EVENTVALIDATION" value="ev" />
          |""".stripMargin
      val fields = BvbSharesForm.aeroPostFields(html)
      assertTrue(
        fields("__VIEWSTATE") == "abc",
        fields("__EVENTTARGET") == BvbSharesForm.AeroButton,
        fields("__ASYNCPOST") == "true",
        fields(BvbSharesForm.ScriptManager).contains(BvbSharesForm.AeroButton)
      )
    },
    test("Retry-After seconds becomes wait millis") {
      val parsed = RateLimitHeaders.parseRetryAfter("3", nowMs = 0)
      assertTrue(parsed.contains(3000L))
    },
    test("Retry-After HTTP-date becomes wait millis") {
      val now = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli
      val parsed = RateLimitHeaders.parseRetryAfter("Mon, 15 Jan 2024 12:00:05 GMT", now)
      assertTrue(parsed.contains(5000L))
    },
    test("X-RateLimit-Reset unix is used as deadline") {
      val now = 1_700_000_000_000L
      val headers = RateLimitHeaders.from(Map("X-RateLimit-Reset" -> "1700000005"), now)
      assertTrue(headers.resetMs.contains(1_700_000_005_000L), headers.waitMs(now) == 5000L)
    },
    test("probe 2xx speeds up and 429 without headers slows and retries") {
      val start = BvbRateLimitState.probe(50)
      val ok = BvbRateLimit.step(start, ProbeEvent.Success)
      val limited = BvbRateLimit.step(ok.state, ProbeEvent.TooManyRequests(RateLimitHeaders(None, None, None, None, Map.empty)))
      assertTrue(
        ok.waitMs < start.intervalMs,
        !ok.retrySame,
        limited.retrySame,
        limited.state.intervalMs >= 200,
        limited.waitMs >= 2000
      )
    }
  )
}
