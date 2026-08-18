package io.greensheep.trading.dataservice

import io.finnhub.client.types.BasicFinancials
import zio.json.*
import zio.test.*

object FinnhubBridgeSpec extends ZIOSpecDefault {

  override def spec = suite("FinnhubBridge")(
    test("translates AAPL fixture into annual and quarterly rows") {
      val source = scala.io.Source.fromResource("aapl-basic-financials.json")
      val json =
        try source.mkString
        finally source.close()
      val financials = json.fromJson[BasicFinancials].toOption.get
      val annual = FinnhubBridge.translateAnnualFinancials(financials)
      val quarterly = FinnhubBridge.translateQuarterlyFinancials(financials)

      assertTrue(
        annual.nonEmpty,
        annual.exists(row => row.year >= 2023 && row.basicEarningsPerShare > 0),
        quarterly.nonEmpty,
        quarterly.exists(row => row.year >= 2023 && row.basicEarningsPerShare != 0.0 || row.revenuePerShare != 0.0)
      )
    },
    test("translates quarterly-only Finnhub payloads") {
      val json =
        """{"symbol":"VCX","series":{"quarterly":{"eps":[{"period":"2024-03-31","v":0.25}],"salesPerShare":[{"period":"2024-03-31","v":1.5}]}}}"""
      val financials = json.fromJson[BasicFinancials].toOption.get
      val annual = FinnhubBridge.translateAnnualFinancials(financials)
      val quarterly = FinnhubBridge.translateQuarterlyFinancials(financials)
      assertTrue(annual.isEmpty, quarterly.size == 1, quarterly.head.year == 2024, quarterly.head.quarter == 1)
    }
  )
}
