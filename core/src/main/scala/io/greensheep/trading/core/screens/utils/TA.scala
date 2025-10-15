package io.greensheep.trading.core.screens.utils

import io.greensheep.trading.core.types.DailyAggregate

object TA {
  def sma(xs: Seq[Double], n: Int): Seq[Option[Double]] =
    if (n <= 0) xs.map(_ => None)
    else xs.indices.map { i =>
      if (i + 1 >= n) Some(xs.slice(i + 1 - n, i + 1).sum / n.toDouble) else None
    }

  def rollingMin(xs: Seq[Double], n: Int): Seq[Option[Double]] =
    if (n <= 0) xs.map(_ => None)
    else xs.indices.map { i =>
      if (i + 1 >= n) Some(xs.slice(i + 1 - n, i + 1).min) else None
    }

  def rollingMax(xs: Seq[Double], n: Int): Seq[Option[Double]] =
    if (n <= 0) xs.map(_ => None)
    else xs.indices.map { i =>
      if (i + 1 >= n) Some(xs.slice(i + 1 - n, i + 1).max) else None
    }

  // Wilder ATR
  def atr(bars: Seq[DailyAggregate], n: Int): Seq[Option[Double]] = {
    if (bars.isEmpty || n <= 0) return Seq.fill(bars.size)(None)
    val trs = bars.indices.map { i =>
      val h = bars(i).h; val l = bars(i).l
      val prevClose = if (i == 0) bars(i).c else bars(i - 1).c
      val a = h - l
      val b = Math.abs(h - prevClose)
      val c = Math.abs(l - prevClose)
      Math.max(a, Math.max(b, c))
    }
    // Wilder smoothing
    val out = Array.fill[Option[Double]](bars.size)(None)
    if (bars.size >= n) {
      val first = trs.take(n).sum / n.toDouble
      out(n - 1) = Some(first)
      var prev = first
      var i = n
      while (i < bars.size) {
        val curr = (prev * (n - 1) + trs(i)) / n.toDouble
        out(i) = Some(curr)
        prev = curr
        i += 1
      }
    }
    out.toSeq
  }
}
