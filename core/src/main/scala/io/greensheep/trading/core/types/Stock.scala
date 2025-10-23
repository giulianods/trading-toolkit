package io.greensheep.trading.core.types

import zio.Chunk
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder, jsonExclude}

import java.time.LocalDate

object Stock {

  implicit val Encoder: JsonEncoder[Stock] = DeriveJsonEncoder.gen[Stock]

  implicit val Decoder: JsonDecoder[Stock] = DeriveJsonDecoder.gen[Stock]
}

case class Stock(
                symbol: String,
                companyName: String,
                ipoDate: LocalDate,
                primaryExchange: String = "",
                description: String = "",
                totalEmployees: Int = 0,
                sicCode: String = "",
                sicDescription: String = "",
                marketCap: Long = 0,
                weightedSharesOutstanding: Long = 0,
                
                @jsonExclude dailyAggregates: Chunk[DailyAggregate] = Chunk.empty
                ){

  def ma(len: Int): Double = {
    val subSeq = dailyAggregates.map(_.c).takeRight(len)
    subSeq.sum / subSeq.length.toDouble
  }

  lazy val currentPrice: Double = dailyAggregates.last.c

  def performanceDays(days: Int): Double = {
    100 * (currentPrice - dailyAggregates.takeRight(days).head.c) / currentPrice
  }

  lazy val ma50: Double = ma(50)

  lazy val ma150: Double = ma(150)

  lazy val ma200: Double = ma(200)

  lazy val high52Week: Double = dailyAggregates.map(_.c).takeRight(200).max

  lazy val low52Week: Double = dailyAggregates.map(_.c).takeRight(200).min

  lazy val perfQ1: Double = currentPrice * 100 / dailyAggregates.takeRight(50).head.c

  lazy val perfQ2: Double = currentPrice * 100 / dailyAggregates.takeRight(100).head.c

  lazy val perfQ3: Double = currentPrice * 100 / dailyAggregates.takeRight(150).head.c

  lazy val perfQ4: Double = currentPrice * 100 / dailyAggregates.takeRight(200).head.c

  def historicalSnapshot(upTo: java.time.LocalDate): Stock = {
    copy(dailyAggregates = dailyAggregates.filter(d => d.t.isBefore(upTo) || d.t.isEqual(upTo)))
  }
}
