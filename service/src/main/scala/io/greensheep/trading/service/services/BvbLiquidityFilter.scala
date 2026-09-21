package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.Quote

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * BVB (and especially AeRO) history typically omits sessions with no trades, so
 * illiquid names show up as sparse bars rather than explicit zero volume.
 */
object BvbLiquidityFilter {

  val MinBars: Int = 60
  /** Bars / expected trading days (calendar span × 5/7). */
  val MinCoverage: Double = 0.5
  val RecentSessions: Int = 20
  val MinRecentAvgVolume: Double = 500.0

  final case class Assessment(
      bars: Int,
      coverage: Double,
      recentAvgVolume: Double,
      liquid: Boolean,
      reason: Option[String]
  )

  def assess(quotes: Seq[Quote]): Assessment = {
    val ordered = quotes.sortBy(_.day.toEpochDay)
    if (ordered.size < MinBars) {
      return Assessment(ordered.size, 0.0, 0.0, liquid = false, Some(s"bars=${ordered.size}<$MinBars"))
    }
    val first = ordered.head.day
    val last = ordered.last.day
    val calendarDays = math.max(1L, ChronoUnit.DAYS.between(first, last) + 1L)
    val expectedSessions = calendarDays * 5.0 / 7.0
    val coverage = ordered.size.toDouble / expectedSessions
    val recent = ordered.takeRight(math.min(RecentSessions, ordered.size))
    val recentAvg = recent.map(_.v.toDouble).sum / recent.size.toDouble

    val reasons = List(
      Option.when(coverage < MinCoverage)(f"coverage=$coverage%.2f<$MinCoverage%.2f"),
      Option.when(recentAvg < MinRecentAvgVolume)(f"avgVol${RecentSessions}=${recentAvg}%.0f<$MinRecentAvgVolume%.0f")
    ).flatten

    Assessment(
      bars = ordered.size,
      coverage = coverage,
      recentAvgVolume = recentAvg,
      liquid = reasons.isEmpty,
      reason = reasons.reduceOption(_ + "," + _)
    )
  }

  def isLiquid(quotes: Seq[Quote]): Boolean = assess(quotes).liquid

  /** Keep symbols whose quote series clears the liquidity bar. */
  def selectLiquid(symbols: Seq[String], quotesBySymbol: Map[String, Seq[Quote]]): Seq[String] =
    symbols.filter(sym => quotesBySymbol.get(sym).exists(isLiquid)).sorted
}
