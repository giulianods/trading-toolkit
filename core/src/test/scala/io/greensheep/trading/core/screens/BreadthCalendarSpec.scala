package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.{DailyAggregate, Stock, StockMarket}
import zio.Chunk
import zio.test.*

import java.time.LocalDate

object BreadthCalendarSpec extends ZIOSpecDefault {

  private def stock(symbol: String, days: Int): Stock = {
    val start = LocalDate.of(2026, 1, 1)
    Stock(
      symbol = symbol,
      companyName = symbol,
      ipoDate = start,
      dailyAggregates = Chunk.fromIterable((0 until days).map { i =>
        val close = 10.0 + i
        DailyAggregate(close, close, close, close, 1, start.plusDays(i))
      })
    )
  }

  override def spec = suite("BreadthIndicators.sessionDates")(
    test("uses the series with the most bars, not a hardcoded ticker") {
      val market = StockMarket(
        Map(
          "NEW" -> stock("NEW", 2),
          "MSFT" -> stock("MSFT", 5)
        )
      )
      val dates = BreadthIndicators.sessionDates(market)
      assertTrue(dates.size == 5, dates.head == LocalDate.of(2026, 1, 1))
    },
    test("returns empty when the market has no stocks") {
      assertTrue(BreadthIndicators.sessionDates(StockMarket(Map.empty)).isEmpty)
    }
  )
}
