package io.greensheep.trading.core.types

import zio.Chunk

import java.time.LocalDate

case class Stock(
                symbol: String,
                companyName: String,
                ipoDate: LocalDate,
                primaryExchange: String,
                dailyAggregates: Chunk[DailyAggregate] = Chunk.empty

                ){

  def ma(len: Int): Double = {
    val subSeq = dailyAggregates.map(_.c).takeRight(len)
    subSeq.sum / subSeq.length.toDouble
  }

  lazy val currentPrice: Double = dailyAggregates.last.c

  lazy val ma50: Double = ma(50)

  lazy val ma150: Double = ma(150)

  lazy val ma200: Double = ma(200)

  lazy val high52Week: Double = dailyAggregates.map(_.c).takeRight(252).max

  lazy val low52Week: Double = dailyAggregates.map(_.c).takeRight(252).min

  lazy val perfQ1 = currentPrice / dailyAggregates.takeRight(63).head.c

  lazy val perfQ2 = currentPrice / dailyAggregates.takeRight(126).head.c

  lazy val perfQ3 = currentPrice / dailyAggregates.takeRight(189).head.c

  lazy val perfQ4 = currentPrice / dailyAggregates.takeRight(252).head.c
}
