package io.greensheep.trading.core.screens.utils

import io.greensheep.trading.core.types.DailyAggregate

import java.time.LocalDate


final case class Contraction(
                              fromDate: LocalDate,
                              toDate: LocalDate,
                              fromPrice: Double,  // swing high
                              toPrice: Double,    // subsequent swing low (or higher low)
                              depthPct: Double,   // (fromPrice - toPrice) / fromPrice
                              bars: Int
                            )

final case class DryUpMetrics(
                               lookbackDays: Int,
                               lastN: Int,
                               isDryUp: Boolean,
                               lastAvgVol: Double,
                               minAvgVolInLookback: Double
                             )

final case class VCPHit(
                         startDate: LocalDate,
                         endDate: LocalDate,
                         pivot: Double,
                         pivotDate: LocalDate,
                         contractions: List[Contraction],
                         atrPercentSlope20vs50: Double,
                         dryUp: DryUpMetrics,
                         volumeTrendDown: Boolean,
                         uptrendOk: Boolean,
                         score: Double // simple composite for ranking
                       )

object VCPParams {
  val Default: VCPParams = VCPParams(finerReversalPct = Some(0.04))
}

final case class VCPParams(
                            minBaseDays: Int = 15,        // ~3 weeks
                            maxBaseDays: Int = 65,        // ~13 weeks
                            minContractions: Int = 2,
                            maxContractions: Int = 5,
                            zigzagReversalPct: Double = 0.06, // 6% reversal defines a swing
                            decreasingDepthTolerancePct: Double = 0.02, // allow slight violations (2%)
                            maxFirstDepthPct: Double = 0.35,  // avoid overly deep first contraction
                            minLastDepthPct: Double = 0.03,   // last contraction typically ~3–10%
                            atrShort: Int = 20,
                            atrLong: Int = 50,
                            volSma: Int = 50,
                            dryUpLastN: Int = 10,
                            dryUpLookbackDays: Int = 120,
                            priorUptrendMa: Int = 50,
                            minUptrendSlopeBps: Double = 0.0, // > 0 rising; set >0 to be stricter
                            breakoutVolumeBoost: Double = 1.5, // 1.5× avg vol (if you check breakouts)
                            finerReversalPct: Option[Double] = None // retry tight setups at a finer swing scale
                          )


object VCPDetector {

  import TA._
  import ZigZag._

  private def percentChange(a: Double, b: Double): Double = (b - a) / a

  private def atrPctSeries(bars: Seq[DailyAggregate], n: Int): Seq[Option[Double]] = {
    val a = atr(bars, n)
    a.indices.map { i =>
      a(i).map(_ / bars(i).c)
    }
  }

  private def slope(a: Double, b: Double): Double = b - a

  private def isRising(xs: Seq[Double], minSlope: Double): Boolean =
    if (xs.size < 2) false else (xs.last - xs.head) > minSlope

  private def simpleMa(xs: Seq[Double], n: Int): Seq[Option[Double]] = sma(xs, n)

  private def avg(xs: Seq[Double]): Double = if (xs.isEmpty) 0.0 else xs.sum / xs.size

  private def avgL(xs: Seq[Long]): Double = if (xs.isEmpty) 0.0 else xs.map(_.toDouble).sum / xs.size

  private def within(v: Double, lo: Double, hi: Double): Boolean = v >= lo && v <= hi

  private def findContractions(swings: List[Swing]): List[Contraction] =
    swings.sliding(2).collect {
      case List(high, low) if high.isHigh && !low.isHigh =>
        val depth = (high.price - low.price) / high.price
        Contraction(high.date, low.date, high.price, low.price, depth, low.idx - high.idx + 1)
    }.toList

  private def decreasingDepths(cs: List[Contraction], tol: Double): Boolean =
    (cs.size == 1 || cs.last.depthPct < cs.head.depthPct) &&
      cs.sliding(2).forall(pair => pair.last.depthPct <= pair.head.depthPct + tol)

  private def baseSpanDays(cs: List[Contraction]): Int =
    if (cs.isEmpty) 0
    else java.time.temporal.ChronoUnit.DAYS.between(cs.head.fromDate, cs.last.toDate).toInt

  private def volumeDryUp(bars: Seq[DailyAggregate], lastN: Int, lookbackDays: Int): DryUpMetrics = {
    if (bars.isEmpty) return DryUpMetrics(lookbackDays, lastN, isDryUp = false, 0.0, 0.0)
    val lastNAvg = avgL(bars.takeRight(lastN).map(_.v.longValue))
    val lb = bars.takeRight(lookbackDays)
    val rolling = sma(lb.map(_.v), lastN).map(_.getOrElse(Double.MaxValue))
    val minAvg = if (rolling.isEmpty) Double.MaxValue else rolling.min
    DryUpMetrics(lookbackDays, lastN, lastNAvg <= minAvg, lastNAvg, minAvg)
  }

  private def volumeTrendDown(bars: Seq[DailyAggregate], window: Int): Boolean = {
    val vols = bars.map(_.v.toDouble)
    val ma = sma(vols, window).flatten
    !isRising(ma, minSlope = 0.0) && ma.nonEmpty // flat or down
  }

  private def priorUptrendOk(bars: Seq[DailyAggregate], maLen: Int, minSlopeBps: Double): Boolean = {
    val closes = bars.map(_.c)
    val ma = sma(closes, maLen).flatten
    val rising = ma.nonEmpty && (ma.last - ma.head) / ma.head > (minSlopeBps / 10000.0)
    val priceAbove = closes.lastOption.exists(p => ma.lastOption.exists(m => p > m))
    rising && priceAbove
  }

  private def atrPctSlope(bars: Seq[DailyAggregate], shortN: Int, longN: Int): Double = {
    val s = atrPctSeries(bars, shortN).flatten
    val l = atrPctSeries(bars, longN).flatten
    if (s.isEmpty || l.isEmpty) 0.0
    else (s.last - avg(s.take(5).takeRight(5))) - (l.last - avg(l.take(5).takeRight(5)))
  }

  // Compute a simple composite score to rank candidates
  private def scoreVcp(cs: List[Contraction], atrSlope: Double, dry: DryUpMetrics, volDown: Boolean, uptrend: Boolean): Double = {
    val depthScore = {
      val depths = cs.map(_.depthPct)
      val mono = if (decreasingDepths(cs, 0.0)) 1.0 else 0.0
      val compression = (depths.headOption.getOrElse(0.0) - depths.lastOption.getOrElse(0.0)).max(0.0)
      mono * 1.0 + compression
    }
    val atrScore = (-atrSlope).max(0.0) // more negative slope → higher score
    val dryScore = if (dry.isDryUp) 1.0 else 0.0
    val volScore = if (volDown) 0.5 else 0.0
    val trend = if (uptrend) 1.0 else 0.0
    depthScore + atrScore + dryScore + volScore + trend
  }

  /**
   * Detect VCP candidates whose last confirmed low is at most lookRight sessions old.
   * Returns at most one hit per symbol slice you run it on; you can also scan rolling windows.
   */
  def detect(
      bars: Seq[DailyAggregate],
      params: VCPParams,
      lookRight: Int = 10,
      uptrendConfirmed: Boolean = false
  ): Option[VCPHit] =
    evaluate(bars, params, lookRight, uptrendConfirmed).toOption

  private[utils] def evaluate(
      bars: Seq[DailyAggregate],
      params: VCPParams,
      lookRight: Int,
      uptrendConfirmed: Boolean = false
  ): Either[(String, List[Contraction]), VCPHit] = {
    val primary = evaluateAtReversal(bars, params, lookRight, params.zigzagReversalPct, uptrendConfirmed)
    params.finerReversalPct.filter(pct => pct > 0 && pct < params.zigzagReversalPct) match {
      case Some(reversalPct) =>
        primary.orElse(evaluateAtReversal(bars, params, lookRight, reversalPct, uptrendConfirmed).orElse(primary))
      case None => primary
    }
  }

  private def evaluateAtReversal(
      bars: Seq[DailyAggregate],
      params: VCPParams,
      lookRight: Int,
      reversalPct: Double,
      uptrendConfirmed: Boolean
  ): Either[(String, List[Contraction]), VCPHit] = {
    if (bars.size < 200) return Left(("history_lt_200", Nil))
    val contractions = findContractions(ZigZag.swings(bars, reversalPct))
    if (contractions.isEmpty) return Left(("no_contractions_found", Nil))

    val recentWindowDate = bars((bars.size - 1 - lookRight.max(0)).max(0)).t
    val recent = contractions.takeRight(params.maxContractions)
    if (recent.isEmpty || recent.last.toDate.isBefore(recentWindowDate))
      return Left(("stale_contraction", recent))

    // Try bases ending at the same recent low. Earlier swings can belong to a different setup.
    val bases = recent.tails.takeWhile(cs => cs.nonEmpty && cs.size >= params.minContractions)
      .filter(cs => baseSpanDays(cs) <= params.maxBaseDays)
      .toList
    if (bases.isEmpty) return Left(("too_few_contractions", recent))
    val longEnough = bases.filter(cs => baseSpanDays(cs) >= params.minBaseDays)
    if (longEnough.isEmpty) return Left(("base_too_short", bases.head))
    val matching = longEnough.find { cs =>
      cs.head.depthPct <= params.maxFirstDepthPct &&
        cs.last.depthPct >= params.minLastDepthPct &&
        decreasingDepths(cs, params.decreasingDepthTolerancePct)
    }
    val chosen = matching match {
      case Some(base) => base
      case None => return Left(("depths_not_ok", longEnough.head))
    }

    // Volatility: ATR% should be trending down (short vs long)
    val atrSlope = atrPctSlope(bars, params.atrShort, params.atrLong)
    val volDown = volumeTrendDown(bars, params.volSma)

    // Volume dry-up into last N bars
    val dry = volumeDryUp(bars, params.dryUpLastN, params.dryUpLookbackDays)

    // The watchlist already confirmed the uptrend through SEPA on these bars.
    val upOk = uptrendConfirmed || priorUptrendOk(bars, params.priorUptrendMa, params.minUptrendSlopeBps)

    if (!upOk) return Left(("prior_uptrend_gate_failed", chosen))

    // Pivot = last swing high BEFORE the last contraction low (i.e., the right-side high)
    // Simpler: pivot is the 'fromPrice' of the last contraction
    val lastC = chosen.last
    val pivot = lastC.fromPrice
    val pivotDate = lastC.fromDate

    val sc = scoreVcp(chosen, atrSlope, dry, volDown, upOk)

    Right(
      VCPHit(
        startDate = chosen.head.fromDate,
        endDate = chosen.last.toDate,
        pivot = pivot,
        pivotDate = pivotDate,
        contractions = chosen,
        atrPercentSlope20vs50 = atrSlope,
        dryUp = dry,
        volumeTrendDown = volDown,
        uptrendOk = upOk,
        score = sc
      )
    )
  }

  /**
   * Optional: given a detected VCP, check if the latest bar produced a breakout.
   * A breakout is defined as close > pivot and volume ≥ boost × 50-dma volume.
   */
  def isBreakoutNow(bars: Seq[DailyAggregate], hit: VCPHit, params: VCPParams): Boolean = {
    if (bars.isEmpty) return false
    val last = bars.last
    val volAvg50 = TA.sma(bars.map(_.v), params.volSma).last.getOrElse(0.0)
    val volOk = if (volAvg50 == 0.0) false else last.v.toDouble >= params.breakoutVolumeBoost * volAvg50
    last.c > hit.pivot && volOk
  }
}
