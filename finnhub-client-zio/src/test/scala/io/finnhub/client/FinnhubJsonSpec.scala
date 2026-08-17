package io.finnhub.client

import io.finnhub.client.types.{FinancialDataPoint, StockSymbol}
import zio.json.*
import zio.test.*

object FinnhubJsonSpec extends ZIOSpecDefault {

  override def spec = suite("Finnhub JSON models")(
    test("decodes stock symbols when optional identifiers are absent") {
      val json =
        """{"description":"APPLE INC","displaySymbol":"AAPL","mic":"XNAS","symbol":"AAPL","type":"Common Stock"}"""

      assertTrue(
        json.fromJson[StockSymbol] == Right(
          StockSymbol(
            description = "APPLE INC",
            displaySymbol = "AAPL",
            mic = "XNAS",
            symbol = "AAPL",
            `type` = "Common Stock"
          )
        )
      )
    },
    test("represents null financial values without using null") {
      val decoded = """{"period":"2024-03-31","v":null}""".fromJson[FinancialDataPoint]
      assertTrue(decoded == Right(FinancialDataPoint("2024-03-31", None)))
    },
    test("accepts numeric strings in financial values") {
      val decoded = """{"period":"2024-03-31","v":"12.34"}""".fromJson[FinancialDataPoint]
      assertTrue(decoded == Right(FinancialDataPoint("2024-03-31", Some(BigDecimal("12.34")))))
    }
  )
}
