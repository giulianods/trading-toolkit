package io.greensheep.trading.service.services

import zio.test.*

object IncomeStatementsSpec extends ZIOSpecDefault {
  override def spec = suite("Income statements")(
    test("reads period totals, computes a signed margin, and joins EPS on the exact fiscal date") {
      val payload = """
        {
          "Financials::Income_Statement": {
            "currency_symbol": "RON",
            "yearly": {"2025-12-31": {"totalRevenue": "500", "netIncome": 50}},
            "quarterly": {
              "2026-06-30": {"totalRevenue": "0", "netIncome": "-3"},
              "2026-03-31": {"totalRevenue": "100", "netIncome": "-25"},
              "2025-12-31": {"totalRevenue": "NA", "netIncome": null}
            }
          },
          "Earnings": {
            "Annual": {"2025-12-31": {"epsActual": 2}},
            "History": {
              "2026-03-31": {"epsActual": "-0.5", "currency": "RON"},
              "2026-06-30": {"epsActual": 3, "currency": "USD"}
            }
          }
        }
      """
      val data = IncomeStatementsService.decode(payload).toOption.get
      assertTrue(
        data.annual.head.revenue.contains(500),
        data.annual.head.eps.contains(2),
        data.quarterly.size == 2,
        data.quarterly.head.netMarginPct.contains(-25),
        data.quarterly.head.eps.contains(-0.5),
        data.quarterly.last.netMarginPct.isEmpty,
        data.quarterly.last.eps.isEmpty
      )
    }
  )
}
