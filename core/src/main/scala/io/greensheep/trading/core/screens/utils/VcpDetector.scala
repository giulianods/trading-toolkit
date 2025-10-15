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
  val Default: VCPParams = VCPParams()
}

final case class VCPParams(
                            minBaseDays: Int = 15,        // ~3 weeks
                            maxBaseDays: Int = 65,        // ~13 weeks
                            minContractions: Int = 3,
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
                            breakoutVolumeBoost: Double = 1.5 // 1.5× avg vol (if you check breakouts)
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

  private def findContractions(swings: List[Swing], bars: Seq[DailyAggregate]): List[Contraction] = {
    // Expect sequence: High -> Low -> High -> Low ... ending near the right side
    val highs = swings.filter(_.isHigh)
    val lows  = swings.filterNot(_.isHigh)
    // Build pairs (High -> next Low) in time order
    val hs = highs.sortBy(_.idx)
    val ls = lows.sortBy(_.idx)

    @scala.annotation.tailrec
    def loop(h: List[Swing], l: List[Swing], acc: List[Contraction]): List[Contraction] = (h, l) match {
      case (h1 :: ht, l1 :: lt) if l1.idx > h1.idx =>
        val depth = (h1.price - l1.price) / h1.price
        val barsCount = l1.idx - h1.idx + 1
        val c = Contraction(h1.date, l1.date, h1.price, l1.price, depth, barsCount)
        // Advance: we want the next contraction from the next high after this low
        val nextH = ht.dropWhile(_.idx <= l1.idx)
        loop(nextH, lt, acc :+ c)
      case _ => acc
    }

    loop(hs, ls, Nil)
  }

  private def decreasingDepths(cs: List[Contraction], tol: Double): Boolean = {
    if (cs.size < 2) return false
    cs.sliding(2).forall { pair =>
      val a = pair.head.depthPct
      val b = pair.last.depthPct
      b <= a + tol && b < a + 1e-12 // must not be meaningfully larger
    }
  }

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
   * Detect VCP candidates ending within the most recent 'lookRight' bars (default: last 10 bars).
   * Returns at most one hit per symbol slice you run it on; you can also scan rolling windows.
   */
  def detect(bars: Seq[DailyAggregate], params: VCPParams, lookRight: Int = 10): Option[VCPHit] = {
    if (bars.size < 200) return None // need history for MAs/volumes
    val swings = ZigZag.swings(bars, params.zigzagReversalPct)
    if (swings.size < 4) return None

    val csAll = findContractions(swings, bars)
    if (csAll.isEmpty) return None

    // Focus on the *last* 3–5 contractions that end near the right edge
    val rightIdx = bars.size - 1
    val recentWindowStart = (rightIdx - lookRight).max(0)
    val recentWindowDate = bars(recentWindowStart).t

    val csNearRight = csAll.filter(x => x.toDate.isAfter(recentWindowDate) || x.toDate.isEqual(recentWindowDate)) match {
      case Nil => csAll.takeRight(params.maxContractions) // fallback: last few
      case xs  => xs
    }

    val cs = csNearRight.takeRight(params.maxContractions).takeRight(params.maxContractions)
    val use = cs.takeRight(params.maxContractions).drop(math.max(0, cs.size - params.maxContractions))

    val candidate = List(use, csAll.takeRight(params.maxContractions)).maxBy(_.size) // pick richer
    val chosen = candidate.takeRight(params.maxContractions)

    if (chosen.size < params.minContractions) return None

    val span = baseSpanDays(chosen)
    if (span < params.minBaseDays || span > params.maxBaseDays) return None

    // Depth checks
    val depthsOk =
      chosen.head.depthPct <= params.maxFirstDepthPct &&
        chosen.last.depthPct >= params.minLastDepthPct &&
        decreasingDepths(chosen, params.decreasingDepthTolerancePct)

    if (!depthsOk) return None

    // Volatility: ATR% should be trending down (short vs long)
    val atrSlope = atrPctSlope(bars, params.atrShort, params.atrLong)
    val volDown = volumeTrendDown(bars, params.volSma)

    // Volume dry-up into last N bars
    val dry = volumeDryUp(bars, params.dryUpLastN, params.dryUpLookbackDays)

    // Prior uptrend
    val upOk = priorUptrendOk(bars, params.priorUptrendMa, params.minUptrendSlopeBps)

    if (!upOk) return None

    // Pivot = last swing high BEFORE the last contraction low (i.e., the right-side high)
    // Simpler: pivot is the 'fromPrice' of the last contraction
    val lastC = chosen.last
    val pivot = lastC.fromPrice
    val pivotDate = lastC.fromDate

    val sc = scoreVcp(chosen, atrSlope, dry, volDown, upOk)

    Some(
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