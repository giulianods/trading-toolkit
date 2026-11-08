package io.greensheep.trading.core.screens

import io.greensheep.trading.core.screens.utils.{VCPDebug, VCPDetector, VCPParams}
import io.greensheep.trading.core.types.{DailyAggregate, Stock, StockMarket}
import zio.{Chunk, ZLayer}
import zio.test.*

import java.time.LocalDate

object VcpSpec extends ZIOSpecDefault {
  private val start = LocalDate.of(2024, 1, 1)
  private val params = VCPParams.Default

  // An initial confirmed low, a long advance, then three progressively shallower pullbacks.
  private val advance = Seq(50.0, 47.0, 44.0, 48.0) ++ (1 to 200).map(i => 50.0 + i * 0.25)
  private val base = Seq(
    120.0, 116.0, 110.0, 100.0, 96.0, 101.0, 105.0, 110.0, 118.0,
    114.0, 109.0, 104.0, 108.0, 112.0, 116.0, 114.0, 111.0, 108.0, 110.0, 115.0
  )

  private def bars(closes: Seq[Double], rightVolume: Double = 1000.0): Chunk[DailyAggregate] =
    Chunk.fromIterable(closes.zipWithIndex.map { case (close, i) =>
      val volume = if (i >= closes.size - 10) rightVolume else 10000.0
      DailyAggregate(close, close * 1.005, close * 0.995, close, volume, start.plusDays(i.toLong))
    })

  private def marketWith(stocks: Stock*): StockMarket = {
    val flat = bars(Seq.fill(stocks.map(_.dailyAggregates.size).max)(80.0))
    val background = (1 to 10).map(i => Stock(s"FLAT$i", s"Flat $i", start, dailyAggregates = flat))
    StockMarket((background ++ stocks).map(stock => stock.symbol -> stock).toMap)
  }

  override def spec = suite("VCP")(
    test("pairs confirmed high-to-low swings after an initial low and reports the pivot") {
      val quotes = bars(advance ++ base)
      val hit = VCPDetector.detect(quotes, params)
      val debug = VCPDebug.detectWithReasons("TEST", quotes, params)
      assertTrue(
        hit.exists(_.contractions.map(_.toPrice) == List(96.0, 104.0, 108.0)),
        hit.exists(_.pivot == 116.0),
        hit.exists(_.dryUp.isDryUp),
        debug.hit == hit,
        debug.reasons.isEmpty
      )
    },
    test("rejects a widening contraction and ignores an old completed base") {
      val widening = bars(advance ++ base.updated(11, 92.0))
      val stale = bars(advance ++ base ++ Seq.fill(11)(115.0))
      assertTrue(
        VCPDetector.detect(widening, params).isEmpty,
        VCPDetector.detect(stale, params).isEmpty,
        VCPDebug.detectWithReasons("STALE", stale, params).reasons.map(_.reason) == List("stale_contraction")
      )
    },
    test("a historical snapshot cannot use a later rebound to confirm the final contraction") {
      val quotes = bars(advance ++ base)
      val stock = Stock("TEST", "Test", start, dailyAggregates = quotes)
      val market = marketWith(stock)
      val snapshot = market.historicalSnapshot(quotes.last.t.minusDays(1))
      val screen = TrendScreens.buildVcpScreen(params.copy(minContractions = 3))
      for {
        before <- screen.provideLayer(ZLayer.succeed(snapshot))
        after <- screen.provideLayer(ZLayer.succeed(market))
      } yield assertTrue(before.isEmpty, after.map(_._1.symbol) == Seq("TEST"))
    },
    test("finds a newer base despite an earlier swing and allows modest variation in depth") {
      val quotes = bars(advance ++ Seq(108.0, 100.0, 110.0) ++ base.updated(17, 100.0))
      val hit = VCPDetector.detect(quotes, params)
      assertTrue(hit.exists(_.contractions.map(_.toPrice) == List(96.0, 104.0, 100.0)))
    },
    test("accepts two contracting pullbacks and can resolve a tight final pullback at the finer scale") {
      val twoPullbacks = bars(advance ++ base.take(8) ++ Seq.fill(4)(110.0) ++ base.slice(8, 15))
      val tight = bars(advance ++ base.updated(16, 112.0).updated(17, 111.0).updated(18, 112.0).updated(19, 116.0))
      assertTrue(
        VCPDetector.detect(twoPullbacks, params).exists(_.contractions.size == 2),
        VCPDetector.detect(tight, params.copy(finerReversalPct = None)).isEmpty,
        VCPDetector.detect(tight, params).exists(_.contractions.map(_.toPrice) == List(96.0, 104.0, 111.0))
      )
    },
    test("the default screen only ranks SEPA members and identifies each result by ticker") {
      val quiet = Stock("QUIET", "Quiet", start, dailyAggregates = bars(Seq.fill(55)(140.0) ++ advance ++ base))
      val loud = Stock("LOUD", "Loud", start, dailyAggregates = bars(advance ++ base, 20000.0))
      val outside = Stock("OUT", "Outside SEPA", start, dailyAggregates = bars(advance.updated(0, 160.0) ++ base))
      val market = marketWith(quiet, loud, outside)
      val layer = ZLayer.succeed(market)
      for {
        sepa <- TrendScreens.SEPA.provideLayer(layer)
        results <- TrendScreens.VolatilityContractionPattern.provideLayer(layer)
      } yield {
        val stats = results.map(_._2)
        assertTrue(
          VCPDetector.detect(outside.dailyAggregates, params).isDefined,
          VCPDetector.detect(quiet.dailyAggregates, params).isEmpty,
          !sepa.exists(_._1.symbol == outside.symbol),
          results.map(_._1.symbol) == Seq("QUIET", "LOUD"),
          stats.map(_.ticker) == Seq("QUIET", "LOUD"),
          stats.forall(_.stats.get("pivot").contains("116.0")),
          stats.map(_.stats("volumeDryUp")) == Seq("true", "false")
        )
      }
    }
  )
}
