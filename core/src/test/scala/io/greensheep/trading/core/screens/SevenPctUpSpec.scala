package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.{DailyAggregate, Stock, StockMarket}
import zio.{Chunk, ZLayer}
import zio.test.*

import java.time.LocalDate

object SevenPctUpSpec extends ZIOSpecDefault {
  private val session = LocalDate.of(2026, 11, 6)

  private def stock(symbol: String, closes: Seq[Double], lastSession: LocalDate = session): Stock = {
    val bars = closes.zipWithIndex.map { case (close, index) =>
      val date = lastSession.minusDays(2L * (closes.size - 1 - index))
      DailyAggregate(close, close, close, close, 1000, date)
    }
    Stock(symbol, symbol, bars.head.t, dailyAggregates = Chunk.fromIterable(bars))
  }

  override def spec = suite("Seven percent screens")(
    test("7%UP uses five close-to-close changes and sorts by market-wide IBD rank") {
      val exactlySeven = stock("EXACT", Vector.fill(15)(10.0) :+ 10.7)
      val largerGain = stock(
        "LARGER",
        Vector.fill(233)(50.0) ++ (110 to 100 by -1).map(_.toDouble) ++
          Vector(100.0, 100.0, 100.0, 100.0, 110.0, 111.0)
      )
      val underFiveDayThreshold = stock("UNDER", Vector.fill(11)(100.0) ++ Vector.fill(4)(90.0) :+ 106.0)
      val shortHistory = stock("SHORT", Vector.fill(14)(100.0) :+ 120.0)
      val stale = stock("STALE", Vector.fill(15)(100.0) :+ 120.0, session.minusDays(1))
      val stocks = Seq(exactlySeven, largerGain, underFiveDayThreshold, shortHistory, stale)
      val market = StockMarket(stocks.map(stock => stock.symbol -> stock).toMap)

      TrendScreens.SevenPctUp.provideLayer(ZLayer.succeed(market)).map { ranked =>
        assertTrue(
          ranked.map(_._1.symbol) == Seq("LARGER", "EXACT"),
          ranked.map(_._2.stats("performanceDays(5)")) == Seq("11.00", "7.00"),
          ranked.map(_._2.stats("IBD rank").toDouble) == Seq(80.0, 20.0),
          ranked.head._2.stats("RSI(15)").toDouble < ranked.last._2.stats("RSI(15)").toDouble
        )
      }
    },
    test("7%DOWN sorts by IBD rank and retains the existing loss filter") {
      val strong = stock("STRONG", Vector.fill(245)(50.0) ++ Vector.fill(4)(100.0) :+ 88.0)
      val weak = stock("WEAK", Vector.fill(249)(100.0) :+ 92.0)
      val atThreshold = stock("AT_THRESHOLD", Vector.fill(249)(100.0) :+ 93.0)
      val stocks = Seq(strong, weak, atThreshold)
      val market = StockMarket(stocks.map(stock => stock.symbol -> stock).toMap)

      TrendScreens.SevenPctDown.provideLayer(ZLayer.succeed(market)).map { ranked =>
        assertTrue(
          ranked.map(_._1.symbol) == Seq("STRONG", "WEAK"),
          ranked.map(_._2.stats("performanceDays(5)")) == Seq("-12.00", "-8.00"),
          ranked.map(_._2.stats("IBD rank").toDouble) == Seq(66.67, 0.0)
        )
      }
    }
  )
}
