package io.greensheep.trading.service.services

import io.finnhub.client.types.StockSymbol
import io.greensheep.trading.core.types.{IncomePeriod, IncomeStatements, MarketId, Markets}
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object FinnhubServicesSpec extends ZIOSpecDefault {
  private def json(value: String): Json.Obj = value.fromJson[Json.Obj].toOption.get
  private def fixture(name: String): Json.Obj = {
    val source = scala.io.Source.fromResource(s"finnhub-aapl-$name.json")
    try json(source.mkString) finally source.close()
  }

  override def spec = suite("US Finnhub")(
    test("registers an isolated market and retains US scheduling") {
      assertTrue(
        Markets.parseEnabled("us-massive,us-alpaca,us-finnhub") == Right(Seq(Markets.UsMassive, Markets.UsAlpaca, Markets.UsFinnhub)),
        MarketId.UsFinnhub.databaseName == "ttk-us-finnhub",
        Markets.UsFinnhub.timeZone == Markets.UsAlpaca.timeZone
      )
    },
    test("only keeps NYSE and Nasdaq common shares, deduplicated in ticker order") {
      def row(symbol: String, mic: String, kind: String) = StockSymbol(description = symbol,
        displaySymbol = symbol, mic = mic, symbol = symbol, `type` = kind)
      val rows = Seq(row("MSFT", "XNAS", "Common Stock"), row("IBM", "XNYS", "Common Stock"),
        row("SPY", "XNYS", "ETP"), row("OTC", "OTCM", "Common Stock"), row("PREF", "XNYS", "Preferred Stock"),
        row("LSE", "XLON", "Common Stock"), row("MSFT", "XNAS", "Common Stock"))
      assertTrue(FinnhubServices.eligibleSymbols(rows).map(_.symbol) == Seq("IBM", "MSFT"))
    },
    test("reads SEC concept arrays, preserves loss margins, and selects the latest restatement") {
      val annual = json("""{"data":[
        {"startDate":"2025-01-01","endDate":"2025-12-31","filedDate":"2026-02-01","report":{"ic":[
          {"concept":"us-gaap_Revenues","unit":"USD","value":500},
          {"concept":"us-gaap_NetIncomeLoss","unit":"USD","value":50}]}},
        {"startDate":"2025-01-01","endDate":"2025-12-31","filedDate":"2026-03-01","report":{"ic":[
          {"concept":"us-gaap_RevenueFromContractWithCustomerExcludingAssessedTax","unit":"USD","value":400},
          {"concept":"us-gaap_NetIncomeLoss","unit":"USD","value":-20},
          {"concept":"us-gaap_EarningsPerShareBasic","unit":"USD/shares","value":-0.5}]}}
      ]}""")
      val data = FinnhubIncomeStatements.decode(annual, json("""{"data":[]}"""))
      assertTrue(data.annual.size == 1, data.annual.head.revenue.contains(400),
        data.annual.head.netMarginPct.contains(-5), data.annual.head.eps.contains(-0.5),
        data.annual.head.currency.contains("USD"), data.quarterly.isEmpty)
    },
    test("uses bank net revenue rather than a component revenue line") {
      val annual = json("""{"data":[{"year":2025,"quarter":0,"startDate":"2025-01-01","endDate":"2025-12-31",
        "report":{"ic":[
          {"concept":"us-gaap_RevenueFromContractWithCustomerExcludingAssessedTax","unit":"usd","value":9615000000},
          {"concept":"us-gaap_RevenuesNetOfInterestExpense","unit":"usd","value":182447000000},
          {"concept":"us-gaap_NetIncomeLoss","unit":"usd","value":57048000000}
        ]}}]}""")
      val period = FinnhubIncomeStatements.decode(annual, json("""{"data":[]}""")).annual.head
      assertTrue(period.revenue.contains(182447000000d),
        period.netMarginPct.contains(57048000000d / 182447000000d * 100))
    },
    test("reconstructs bank net revenue from net interest and total noninterest income") {
      val annual = json("""{"data":[{"year":2025,"quarter":0,"startDate":"2025-01-01","endDate":"2025-12-31",
        "report":{"ic":[
          {"concept":"us-gaap_InterestIncomeExpenseNet","unit":"usd","value":936923000},
          {"concept":"us-gaap_NoninterestIncome","unit":"usd","value":271035000},
          {"concept":"us-gaap_NoninterestIncomeOther","unit":"usd","value":32687000},
          {"concept":"us-gaap_NetIncomeLoss","unit":"usd","value":412154000}
        ]}}]}""")
      val period = FinnhubIncomeStatements.decode(annual, json("""{"data":[]}""")).annual.head
      assertTrue(period.revenue.contains(1207958000d), period.currency.contains("USD"),
        period.netMarginPct.contains(412154000d / 1207958000d * 100))
    },
    test("recognizes consolidated utility revenue and historical net service revenue") {
      val annual = json("""{"data":[
        {"startDate":"2024-01-01","endDate":"2024-12-31","report":{"ic":{"SalesRevenueServicesNet":80,"NetIncomeLoss":8}}},
        {"startDate":"2025-01-01","endDate":"2025-12-31","report":{"ic":{"RegulatedAndUnregulatedOperatingRevenue":100,"NetIncomeLoss":10}}}
      ]}""")
      val data = FinnhubIncomeStatements.decode(annual, json("""{"data":[]}"""))
      assertTrue(data.annual.flatMap(_.revenue) == Seq(80, 100), data.annual.forall(_.netMarginPct.contains(10)))
    },
    test("normalizes SEC currency unit IDs without guessing opaque references") {
      val units = Seq("usd", "_USD", "U_USD", "UNIT_USD", "UNIT_STANDARD_USD_example", "EUR", "UREF_123", "USD/shares")
      val annual = json("""{"data":[{"startDate":"2025-01-01","endDate":"2025-12-31",
        "report":{"ic":[{"concept":"us-gaap_Revenues","unit":"U_USD","value":100},
          {"concept":"us-gaap_NetIncomeLoss","unit":"U_USD","value":10}]}}]}""")
      val data = FinnhubIncomeStatements.decode(annual, json("""{"data":[]}"""))
      val legacy = data.copy(annual = data.annual.map(_.copy(currency = Some("_USD"))))
      assertTrue(units.map(FinnhubIncomeStatements.currencyCode) ==
        Seq.fill(5)(Some("USD")) ++ Seq(Some("EUR"), None, None),
        data.annual.head.currency.contains("USD"), data.annual.head.netMarginPct.contains(10),
        FinnhubIncomeStatements.normalizeCurrencies(legacy) == data)
    },
    test("a quarterly fetch failure preserves annual data and previous quarters") {
      val annual = json("""{"data":[{"startDate":"2025-01-01","endDate":"2025-12-31",
        "report":{"ic":{"Revenues":100,"NetIncomeLoss":10}}}]}""")
      val old = IncomePeriod(java.time.LocalDate.parse("2025-03-31"), Some("USD"), Some(20), Some(2), Some(10), None)
      FinnhubServices.fetchStatements(frequency => if (frequency == "annual") ZIO.succeed(annual)
        else ZIO.fail(new RuntimeException("HTTP 500")), IncomeStatements(Seq.empty, Seq(old)))
        .map(result => assertTrue(result.failedFrequencies == Seq("quarterly"),
          result.statements.annual.head.revenue.contains(100), result.statements.quarterly == Seq(old)))
    },
    test("an annual fetch failure still fetches quarters and retains available history") {
      val quarterly = json("""{"data":[{"year":2026,"quarter":1,"startDate":"2026-01-01","endDate":"2026-03-31",
        "report":{"ic":{"Revenues":30,"NetIncomeLoss":3}}}]}""")
      val old = IncomePeriod(java.time.LocalDate.parse("2025-12-31"), Some("USD"), Some(100), Some(10), Some(10), None)
      for {
        calls <- Ref.make(Seq.empty[String])
        result <- FinnhubServices.fetchStatements(frequency => calls.update(_ :+ frequency) *>
          (if (frequency == "annual") ZIO.fail(new RuntimeException("HTTP 500")) else ZIO.succeed(quarterly)),
          IncomeStatements(Seq(old), Seq(old)))
        requested <- calls.get
      } yield assertTrue(requested == Seq("annual", "quarterly"), result.failedFrequencies == Seq("annual"),
        result.statements.annual == Seq(old), result.statements.quarterly.flatMap(_.revenue) == Seq(100, 30))
    },
    test("successful empty responses replace old data without being treated as failures") {
      val old = IncomePeriod(java.time.LocalDate.parse("2025-12-31"), None, Some(100), None, None, None)
      FinnhubServices.fetchStatements(_ => ZIO.succeed(json("""{"data":[]}""")), IncomeStatements(Seq(old), Seq(old)))
        .map(result => assertTrue(result.failedFrequencies.isEmpty, result.statements == IncomeStatements.empty))
    },
    test("accepts legacy concept maps and excludes cumulative YTD periods and invalid dates") {
      val quarterly = json("""{"data":[
        {"startDate":"2026-01-01","endDate":"2026-03-31 00:00:00","quarter":1,"report":{"ic":{"Revenues":100,"NetIncomeLoss":10,"EarningsPerShareBasic":1}}},
        {"startDate":"2026-01-01","endDate":"2026-06-30","report":{"ic":{"Revenues":300}}},
        {"startDate":"2026-01-01","endDate":"invalid","report":{"ic":{"Revenues":999}}}
      ]}""")
      val data = FinnhubIncomeStatements.decode(json("""{"data":[]}"""), quarterly)
      assertTrue(data.quarterly.size == 1, data.quarterly.head.netMarginPct.contains(10))
    },
    test("maps saved live AAPL filings with a September fiscal year into annual and individual quarters") {
      val data = FinnhubIncomeStatements.decode(fixture("annual"), fixture("quarterly"))
      assertTrue(data.annual.size == 3, data.quarterly.size == 12,
        data.quarterly.find(_.date.toString == "2026-06-27").flatMap(_.revenue).contains(109417000000d),
        data.quarterly.find(_.date.toString == "2026-09-26").flatMap(_.revenue).contains(114477000000d),
        data.quarterly.forall(_.currency.contains("USD")))
    },
    test("derives individual Q2, Q3 and Q4 totals from matching fiscal YTD statements") {
      val annual = json("""{"data":[{"year":2025,"quarter":0,"startDate":"2025-01-01","endDate":"2025-12-31",
        "report":{"ic":{"Revenues":1000,"NetIncomeLoss":100,"EarningsPerShareBasic":10}}}]}""")
      val quarterly = json("""{"data":[
        {"year":2025,"quarter":1,"startDate":"2025-01-01","endDate":"2025-03-31","report":{"ic":{"Revenues":100,"NetIncomeLoss":10,"EarningsPerShareBasic":1}}},
        {"year":2025,"quarter":2,"startDate":"2025-01-01","endDate":"2025-06-30","report":{"ic":{"Revenues":300,"NetIncomeLoss":30,"EarningsPerShareBasic":3}}},
        {"year":2025,"quarter":3,"startDate":"2025-01-01","endDate":"2025-09-30","report":{"ic":{"Revenues":600,"NetIncomeLoss":60,"EarningsPerShareBasic":6}}}
      ]}""")
      val data = FinnhubIncomeStatements.decode(annual, quarterly)
      assertTrue(data.quarterly.flatMap(_.revenue) == Seq(100, 200, 300, 400),
        data.quarterly.flatMap(_.netIncome) == Seq(10, 20, 30, 40),
        data.quarterly.forall(_.netMarginPct.contains(10)), data.quarterly.head.eps.contains(1),
        data.quarterly.tail.forall(_.eps.isEmpty))
    }
  )
}
