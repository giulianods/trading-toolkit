package io.greensheep.trading.core.screens.utils

import io.greensheep.trading.core.types.DailyAggregate

import java.time.LocalDate

object ZigZag {
  enum Dir { case Up, Down, None }

  final case class Swing(idx: Int, price: Double, date: LocalDate, isHigh: Boolean)

  /**
   * Return confirmed swings where price reverses by >= reversalPct from the last extreme.
   * Uses closes for simplicity; you can swap to highs/lows if you prefer.
   */
  def swings(bars: Seq[DailyAggregate], reversalPct: Double): List[Swing] = {
    if (bars.size < 3) return Nil
    var dir: Dir = Dir.None
    var lastExtremeIdx = 0
    var lastExtremePrice = bars.head.c
    var swings = List.empty[Swing]

    for (i <- 1 until bars.size) {
      val px = bars(i).c
      dir match {
        case Dir.None =>
          if (px > lastExtremePrice) { dir = Dir.Up; lastExtremeIdx = i; lastExtremePrice = px }
          else if (px < lastExtremePrice) { dir = Dir.Down; lastExtremeIdx = i; lastExtremePrice = px }

        case Dir.Up =>
          if (px >= lastExtremePrice) { lastExtremeIdx = i; lastExtremePrice = px }
          else {
            val drawdown = (lastExtremePrice - px) / lastExtremePrice
            if (drawdown >= reversalPct) {
              swings = swings :+ Swing(lastExtremeIdx, lastExtremePrice, bars(lastExtremeIdx).t, isHigh = true)
              dir = Dir.Down
              lastExtremeIdx = i
              lastExtremePrice = px
            }
          }

        case Dir.Down =>
          if (px <= lastExtremePrice) { lastExtremeIdx = i; lastExtremePrice = px }
          else {
            val bounce = (px - lastExtremePrice) / lastExtremePrice
            if (bounce >= reversalPct) {
              swings = swings :+ Swing(lastExtremeIdx, lastExtremePrice, bars(lastExtremeIdx).t, isHigh = false)
              dir = Dir.Up
              lastExtremeIdx = i
              lastExtremePrice = px
            }
          }
      }
    }

    swings
  }
}
