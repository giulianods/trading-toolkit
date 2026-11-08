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
                         leadingContractions: List[Contraction],
                         baseDrawdownPct: Double,
                         atrPercentSlope20vs50: Double,
                         dryUp: DryUpMetrics,
                         volumeTrendDown: Boolean,
                         uptrendOk: Boolean,
                         score: Double // simple composite for ranking
                       )

object VCPParams {
  val Default: VCPParams = VCPParams(finerReversalPcts = List(0.04, 0.03), tightShelf = true)
}

final case class VCPParams(
                            minBaseDays: Int = 15,        // ~3 weeks
                            maxBaseDays: Int = 120,       // calendar days
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
                            finerReversalPcts: List[Double] = Nil,
                            tightShelf: Boolean = false,
                            maxBaseDrawdownPct: Double = 0.30
                          )


object VCPDetector {

  import TA._
  import ZigZag._

  private val MinShelfDepthPct = 0.01

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
    if (bars.size < 200) return Left(("history_lt_200", Nil))
    val primarySwings = findContractions(ZigZag.swings(bars, params.zigzagReversalPct))
    val primary = evaluateContractions(bars, params, lookRight, primarySwings, primarySwings, uptrendConfirmed)
    primary.orElse {
      val finer = params.finerReversalPcts.iterator
        .filter(pct => pct > 0 && pct < params.zigzagReversalPct)
        .map { pct =>
          val contractions = findContractions(ZigZag.swings(bars, pct))
          evaluateContractions(bars, params, lookRight, contractions, primarySwings, uptrendConfirmed)
        }
        .collectFirst { case Right(hit) => hit }
      finer.orElse {
        Option.when(params.tightShelf)(findTightShelf(bars, primarySwings)).flatten
          .flatMap(cs => evaluateContractions(bars, params.copy(minLastDepthPct = MinShelfDepthPct), lookRight, cs, primarySwings, uptrendConfirmed).toOption)
      }.map(Right(_)).getOrElse(primary)
    }
  }

  private def findTightShelf(bars: Seq[DailyAggregate], contractions: List[Contraction]): Option[List[Contraction]] = {
    val shelf = bars.takeRight(10)
    val highIndex = shelf.indices.maxBy(i => shelf(i).h)
    val high = shelf(highIndex)
    val fullRange = 1 - shelf.iterator.map(_.l).min / high.h
    if (fullRange > 0.04 || bars.last.c < high.h * (1 - fullRange / 2)) return None

    for {
      previous <- contractions.findLast(_.toDate.isBefore(shelf.head.t))
      low <- shelf.drop(highIndex + 1).minByOption(_.l)
      depth = 1 - low.l / high.h
      if depth >= MinShelfDepthPct && previous.depthPct >= 2 * fullRange && low.l >= previous.toPrice
    } yield {
      val duration = shelf.indexWhere(_.t == low.t) - highIndex + 1
      List(previous, Contraction(high.t, low.t, high.h, low.l, depth, duration))
    }
  }

  private def rightEdgeFailure(bars: Seq[DailyAggregate], chosen: List[Contraction]): Option[String] = {
    val last = chosen.last
    val afterLow = bars.dropWhile(b => !b.t.isAfter(last.toDate))
    val close = bars.last.c
    if (afterLow.exists(_.c < last.toPrice)) Some("closed_below_support")
    else if (close > last.fromPrice * 1.05) Some("extended_above_pivot")
    else if (close > last.fromPrice * 1.03 && afterLow.size >= 6 && afterLow.takeRight(6).forall(_.c > last.fromPrice))
      Some("sustained_move_above_pivot")
    else {
      // A breakout through the previous pivot must hold that mini-base's low.
      chosen.dropRight(1).lastOption.filter(_.fromPrice < last.fromPrice).flatMap { previous =>
        bars.find(_.t == previous.toDate).flatMap { support =>
          val retest = bars.dropWhile(_.t.isBefore(last.fromDate))
          Option.when(retest.exists(_.l < support.l * 0.995))("failed_mini_base")
        }
      }
    }
  }

  private def evaluateContractions(
      bars: Seq[DailyAggregate],
      params: VCPParams,
      lookRight: Int,
      contractions: List[Contraction],
      coarseContractions: List[Contraction],
      uptrendConfirmed: Boolean
  ): Either[(String, List[Contraction]), VCPHit] = {
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
    val contracting = longEnough.filter { cs =>
      cs.head.depthPct <= params.maxFirstDepthPct &&
        cs.last.depthPct >= params.minLastDepthPct &&
        cs.last.depthPct <= 0.10 &&
        decreasingDepths(cs, params.decreasingDepthTolerancePct)
    }
    if (contracting.isEmpty) return Left(("depths_not_ok", longEnough.head))

    // Pullbacks should form one base around its initial resistance. Allow it to
    // slope upward, but keep its swing highs within one initial pullback of that level.
    val chosen = contracting.find { cs =>
      val first = cs.head
      val ceiling = first.fromPrice + (first.fromPrice - first.toPrice)
      cs.forall(_.fromPrice <= ceiling)
    } match {
      case Some(base) => base
      case None => return Left(("base_trended_higher", contracting.head))
    }
    rightEdgeFailure(bars, chosen) match {
      case Some(reason) => return Left((reason, chosen))
      case None => ()
    }

    val first = chosen.head
    val last = chosen.last
    val earliest = last.toDate.minusDays(params.maxBaseDays.toLong)
    val priorSwings = coarseContractions
      .dropWhile(_.fromDate.isBefore(earliest))
      .takeWhile(_.toDate.isBefore(first.fromDate))
    val resistance = priorSwings.foldLeft(first.fromPrice)((high, swing) => math.max(high, swing.fromPrice))
    // Keep the earlier tests of the same resistance, including the main correction.
    val leading = priorSwings.dropWhile(_.fromPrice < resistance * 0.95)
    val baseStart = leading.headOption.fold(first.fromDate)(_.fromDate)
    val (_, baseDrawdown) = bars.iterator
      .dropWhile(_.t.isBefore(baseStart))
      .takeWhile(bar => !bar.t.isAfter(last.toDate))
      .foldLeft((0.0, 0.0)) { case ((peak, drawdown), bar) =>
        val high = math.max(peak, bar.c)
        (high, math.max(drawdown, 1 - bar.c / high))
      }
    if (baseDrawdown > params.maxBaseDrawdownPct)
      return Left(("base_drawdown_too_deep", leading ++ chosen))

    // Volatility: ATR% should be trending down (short vs long)
    val atrSlope = atrPctSlope(bars, params.atrShort, params.atrLong)
    val volDown = volumeTrendDown(bars, params.volSma)

    // Volume dry-up into last N bars
    val dry = volumeDryUp(bars, params.dryUpLastN, params.dryUpLookbackDays)

    // The watchlist already confirmed the uptrend through SEPA on these bars.
    val upOk = uptrendConfirmed || priorUptrendOk(bars, params.priorUptrendMa, params.minUptrendSlopeBps)

    if (!upOk) return Left(("prior_uptrend_gate_failed", chosen))

    val sc = scoreVcp(chosen, atrSlope, dry, volDown, upOk)

    Right(
      VCPHit(
        startDate = baseStart,
        endDate = last.toDate,
        pivot = last.fromPrice,
        pivotDate = last.fromDate,
        contractions = chosen,
        leadingContractions = leading,
        baseDrawdownPct = baseDrawdown,
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
