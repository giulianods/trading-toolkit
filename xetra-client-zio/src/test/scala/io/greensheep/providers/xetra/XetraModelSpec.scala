package io.greensheep.providers.xetra

import io.greensheep.providers.xetra.datatypes.PriceHistoryResponse
import zio.json.*
import zio.test.*

import java.security.MessageDigest
import java.time.LocalDate

object XetraModelSpec extends ZIOSpecDefault {

  override def spec = suite("Xetra client models")(
    test("history URL includes mic=XETR, cleanSplit and ISIN date range") {
      val url = XetraHistoryRequest.buildUrl("DE0007164600", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
      assertTrue(
        url.startsWith("https://api.boerse-frankfurt.de/v1/data/price_history?"),
        url.contains("isin=DE0007164600"),
        url.contains("mic=XETR"),
        url.contains("cleanSplit=true"),
        url.contains("cleanPayout=false"),
        url.contains("minDate=2024-01-01"),
        url.contains("maxDate=2024-01-31")
      )
    },
    test("decodes price_history JSON into bars") {
      val json =
        """{"data":[{"date":"2024-01-02","open":150.0,"high":152.0,"low":149.0,"close":151.5,"turnoverPieces":12345}],"totalCount":1,"isin":"DE0007164600"}"""
      val decoded = json.fromJson[PriceHistoryResponse]
      assertTrue(
        decoded.isRight,
        decoded.toOption.exists(_.bars.head.close == 151.5),
        decoded.toOption.exists(_.bars.head.volume == 12345)
      )
    },
    test("empty data is an empty series") {
      val decoded = """{"data":[],"totalCount":0}""".fromJson[PriceHistoryResponse]
      assertTrue(decoded.toOption.exists(_.bars.isEmpty))
    },
    test("T7 CSV fixture yields SAP and ALV") {
      val csv =
        """Product Status;Instrument;Mnemonic;ISIN;Instrument Type;Market Segment
          |Active;SAP SE;SAP;DE0007164600;CS;045
          |Active;Allianz SE;ALV;DE0008404005;CS;046
          |Active;Some ETF;EXS1;DE0002635307;ETF;045
          |Active;Scale Co;SCL;DE000A1AAAA1;CS;054
          |""".stripMargin
      val instruments = XetraCsvParser.parse(csv)
      assertTrue(
        instruments.exists(i => i.ticker == "SAP" && i.isin == "DE0007164600"),
        instruments.exists(i => i.ticker == "ALV" && i.isin == "DE0008404005"),
        instruments.size == 2
      )
    },
    test("trace-id header is MD5 of Client-Date plus full URL plus salt") {
      val url = "https://api.boerse-frankfurt.de/v1/data/price_history?isin=DE0007164600&mic=XETR"
      val salt = "w4ivc1ATTGta6njAZzMbkL3kJwxMfEAKDa3MNr"
      val signed = XetraTraceHeaders.signedHeaders(url, salt)
      val expected = md5Hex(signed.clientDate + url + salt)
      assertTrue(signed.traceId == expected, signed.security.length == 32)
    },
    test("trace signing uses the wire URL after decode") {
      val raw = XetraHistoryRequest.buildUrl("DE0007164600", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31))
      val wire = zio.http.URL.decode(raw).toOption.get.toJavaURI.toASCIIString
      val salt = "test-salt"
      val signed = XetraTraceHeaders.signedHeaders(wire, salt)
      val expected = md5Hex(signed.clientDate + wire + salt)
      assertTrue(signed.traceId == expected, wire != raw)
    },
    test("sanitizes non-finite JSON numbers") {
      val json = """{"data":[{"date":"2024-01-02","open":NaN,"high":.5,"low":null,"close":1.2,"turnoverPieces":10}]}"""
      val decoded = XetraJson.sanitize(json).fromJson[PriceHistoryResponse]
      assertTrue(decoded.toOption.exists(_.bars.head.open == 0.0), decoded.toOption.exists(_.bars.head.high == 0.5))
    },
    test("Retry-After seconds becomes wait millis") {
      val parsed = RateLimitHeaders.parseRetryAfter("3", nowMs = 0)
      assertTrue(parsed.contains(3000L))
    },
    test("probe 2xx speeds up and 429 without headers slows and retries") {
      val start = XetraRateLimitState.probe(50)
      val ok = XetraRateLimit.step(start, ProbeEvent.Success)
      val limited = XetraRateLimit.step(ok.state, ProbeEvent.TooManyRequests(RateLimitHeaders(None, None, None, None, Map.empty)))
      assertTrue(
        ok.waitMs < start.intervalMs,
        !ok.retrySame,
        limited.retrySame,
        limited.state.intervalMs >= 200,
        limited.waitMs >= 2000
      )
    },
    test("location header is case-insensitive") {
      val headers = Map("Location" -> "https://example.com/next")
      assertTrue(XetraHttp.locationHeader(headers).contains("https://example.com/next"))
    },
    test("resolveRedirect accepts absolute Deutsche Börse CSV locations") {
      val location =
        "https://www.cashmarket.deutsche-boerse.com/resource/blob/1528/8e34798266f78fe8811bd24387445b2b/data/t7-xetr-allTradableInstruments.csv"
      val resolved = zio.Unsafe.unsafe { implicit u =>
        zio.Runtime.default.unsafe.run(XetraHttp.resolveRedirect(location)).getOrThrow()
      }
      assertTrue(resolved.host.exists(_.contains("cashmarket.deutsche-boerse.com")))
    }
  )

  private def md5Hex(value: String): String = {
    val digest = MessageDigest.getInstance("MD5")
    digest.digest(value.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }
}
