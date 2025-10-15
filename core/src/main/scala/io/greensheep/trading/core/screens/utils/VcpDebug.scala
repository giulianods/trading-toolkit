package io.greensheep.trading.core.screens.utils

import io.greensheep.trading.core.types.DailyAggregate

object VCPDebug {
  import VCPDetector._
  import ZigZag._
  import TA._

  final case class Failure(reason: String)
  final case class DebugResult(
                                symbol: String,
                                hit: Option[VCPHit],
                                reasons: List[Failure],
                                candidateContractions: List[Contraction] = Nil
                              )

  def detectWithReasons(symbol: String, bars: Seq[DailyAggregate], p: VCPParams, lookRight: Int = 10): DebugResult = {
    var fails = List.empty[Failure]
    if (bars.size < 200) return DebugResult(symbol, None, List(Failure("history_lt_200")))
    val swings = ZigZag.swings(bars, p.zigzagReversalPct)
    if (swings.size < 4) return DebugResult(symbol, None, List(Failure("too_few_swings")))

    val csAll = {
      val highs = swings.filter(_.isHigh)
      val lows  = swings.filterNot(_.isHigh)
      if (highs.isEmpty || lows.isEmpty) Nil else {
        var hs = highs.sortBy(_.idx); var ls = lows.sortBy(_.idx); var acc = List.empty[Contraction]
        while (hs.nonEmpty && ls.nonEmpty && ls.head.idx > hs.head.idx) {
          val h = hs.head; val l = ls.head
          val depth = (h.price - l.price) / h.price
          val barsCnt = l.idx - h.idx + 1
          acc :+= Contraction(h.date, l.date, h.price, l.price, depth, barsCnt)
          hs = hs.dropWhile(_.idx <= l.idx)
          ls = ls.tail
        }
        acc
      }
    }
    if (csAll.isEmpty) return DebugResult(symbol, None, List(Failure("no_contractions_found")))

    val rightIdx = bars.size - 1
    val recentWindowDate = bars((rightIdx - lookRight).max(0)).t
    val csNearRight = csAll.filter(c => !c.toDate.isBefore(recentWindowDate))
    val chosen = (if (csNearRight.nonEmpty) csNearRight else csAll).takeRight(p.maxContractions)

    if (chosen.size < p.minContractions) return DebugResult(symbol, None, List(Failure("too_few_contractions")), chosen)

    val span = java.time.temporal.ChronoUnit.DAYS.between(chosen.head.fromDate, chosen.last.toDate).toInt
    if (span < p.minBaseDays) fails ::= Failure(s"base_too_short_$span")
    if (span > p.maxBaseDays) fails ::= Failure(s"base_too_long_$span")

    val depthsOk =
      chosen.head.depthPct <= p.maxFirstDepthPct &&
        chosen.last.depthPct >= p.minLastDepthPct &&
        {
          val tol = p.decreasingDepthTolerancePct
          chosen.sliding(2).forall { w =>
            val a = w.head.depthPct; val b = w.last.depthPct
            b <= a + tol && b < a + 1e-12
          }
        }

    if (!depthsOk) {
      val depths = chosen.map(c => f"${c.depthPct*100}%.1f").mkString(",")
      fails ::= Failure(s"depths_not_ok[$depths]")
    }

    val atrSlope = {
      val s = TA.atr(bars, p.atrShort).zip(bars).flatMap{case (o,b)=>o.map(_/b.c)}
      val l = TA.atr(bars, p.atrLong).zip(bars).flatMap{case (o,b)=>o.map(_/b.c)}
      if (s.isEmpty || l.isEmpty) 0.0 else (s.last - s.takeRight(5).sum/5.0) - (l.last - l.takeRight(5).sum/5.0)
    }
    // prefer negative atrSlope (contracting)
    if (atrSlope >= 0.0) fails ::= Failure(f"atrpct_slope_not_negative_${atrSlope}%.5f")

    val vols = bars.map(_.v.toDouble)
    val volMa = TA.sma(vols, p.volSma).flatten
    val volDown = volMa.nonEmpty && !(volMa.last > volMa.head)
    if (!volDown) fails ::= Failure("volume_trend_not_down")

    val dry = {
      val lastN = p.dryUpLastN
      val lb = bars.takeRight(p.dryUpLookbackDays)
      val rolling = TA.sma(lb.map(_.v.toDouble), lastN).map(_.getOrElse(Double.MaxValue))
      val minAvg = if (rolling.isEmpty) Double.MaxValue else rolling.min
      val lastAvg = bars.takeRight(lastN).map(_.v.toDouble).sum / lastN
      (lastAvg <= minAvg, lastAvg, minAvg)
    }
    if (!dry._1) fails ::= Failure(f"no_volume_dryup_lastAvg=${dry._2}%.0f>minAvg=${dry._3}%.0f")

    val closes = bars.map(_.c)
    val ma50 = TA.sma(closes, p.priorUptrendMa).flatten
    val upOk = ma50.nonEmpty &&
      (ma50.last - ma50.head)/ma50.head > (p.minUptrendSlopeBps/10000.0) &&
      closes.lastOption.exists(_ > ma50.last)
    if (!upOk) fails ::= Failure("prior_uptrend_gate_failed")

    if (fails.nonEmpty) DebugResult(symbol, None, fails.reverse, chosen)
    else {
      val lastC = chosen.last
      val hit = VCPHit(
        startDate = chosen.head.fromDate,
        endDate   = chosen.last.toDate,
        pivot     = lastC.fromPrice,
        pivotDate = lastC.fromDate,
        contractions = chosen,
        atrPercentSlope20vs50 = atrSlope,
        dryUp = DryUpMetrics(p.dryUpLookbackDays, p.dryUpLastN, isDryUp = dry._1, lastAvgVol = dry._2, minAvgVolInLookback = dry._3),
        volumeTrendDown = volDown,
        uptrendOk = upOk,
        score = 0.0
      )
      DebugResult(symbol, Some(hit), Nil, chosen)
    }
  }
}
