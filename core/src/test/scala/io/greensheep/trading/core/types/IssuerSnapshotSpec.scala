package io.greensheep.trading.core.types

import zio.Chunk
import zio.test.*

import java.time.LocalDate

object IssuerSnapshotSpec extends ZIOSpecDefault {
  override def spec = test("historical screens pick one listing per issuer using only bars through the snapshot date") {
    val first = LocalDate.parse("2026-01-05")
    val next = first.plusDays(1)
    def stock(ticker: String, volumes: (Double, Double)): Stock =
      Stock(symbol = ticker, companyName = ticker, description = "", primaryExchange = "", ipoDate = first, dailyAggregates = Chunk(
        DailyAggregate(10, 10, 10, 10, volumes._1, first),
        DailyAggregate(10, 10, 10, 10, volumes._2, next)
      ))

    val listings = Seq(stock("ABC-A.TO", (500, 50)), stock("ABC-B.TO", (300, 2000)), stock("ABC.V", (100, 100)))
    val market = StockMarket(listings.map(stock => stock.symbol -> stock).toMap,
      Map("ABC-A.TO" -> "ABC", "ABC-B.TO" -> "ABC"))

    assertTrue(
      market.historicalSnapshot(first).stocks.keySet == Set("ABC-A.TO", "ABC.V"),
      market.historicalSnapshot(next).stocks.keySet == Set("ABC-B.TO", "ABC.V")
    )
  }
}
