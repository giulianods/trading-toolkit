package io.finnhub.client

import io.finnhub.client.types.{BasicFinancials, FinancialDataPoint, StockSymbol}
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
    },
    test("decodes a saved AAPL basic-financials fixture with extra metric fields") {
      val source = scala.io.Source.fromResource("aapl-basic-financials.json")
      val json =
        try source.mkString
        finally source.close()
      val decoded = json.fromJson[BasicFinancials]
      assertTrue(
        decoded.map(_.symbol) == Right("AAPL"),
        decoded.map(_.series.annual.eps.nonEmpty) == Right(true),
        decoded.map(_.series.annual.salesPerShare.nonEmpty) == Right(true),
        decoded.map(_.series.quarterly.eps.nonEmpty) == Right(true)
      )
    },
    test("defaults missing annual series lists to empty") {
      val json =
        """{"symbol":"SPARSE","series":{"annual":{"eps":[{"period":"2024-12-31","v":1.0}]},"quarterly":{}}}"""
      val decoded = json.fromJson[BasicFinancials]
      assertTrue(
        decoded.map(_.symbol) == Right("SPARSE"),
        decoded.map(_.series.annual.eps.size) == Right(1),
        decoded.map(_.series.annual.bookValue) == Right(Nil),
        decoded.map(_.series.quarterly.eps) == Right(Nil)
      )
    },
    test("decodes when annual is omitted from series") {
      val json =
        """{"symbol":"VCX","metric":{},"series":{"quarterly":{"eps":[{"period":"2024-03-31","v":0.1}]}}}"""
      val decoded = json.fromJson[BasicFinancials]
      assertTrue(
        decoded.map(_.symbol) == Right("VCX"),
        decoded.map(_.series.annual.eps) == Right(Nil),
        decoded.map(_.series.quarterly.eps.size) == Right(1)
      )
    },
    test("decodes empty or missing series as empty financials") {
      val emptySeries = """{"symbol":"VCX","series":{}}""".fromJson[BasicFinancials]
      val missingSeries = """{"symbol":"VCX"}""".fromJson[BasicFinancials]
      val nullSeries = """{"symbol":"VCX","series":null}""".fromJson[BasicFinancials]
      assertTrue(
        emptySeries.map(_.series.annual.eps) == Right(Nil),
        emptySeries.map(_.series.quarterly.eps) == Right(Nil),
        missingSeries.map(_.series.annual.eps) == Right(Nil),
        nullSeries.map(_.series.quarterly.eps) == Right(Nil)
      )
    }
  )
}
