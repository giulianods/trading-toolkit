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

  lazy val high52Week: Double = dailyAggregates.map(_.c).takeRight(250).max

  lazy val low52Week: Double = dailyAggregates.map(_.c).takeRight(250).min

  lazy val perfQ1: Double = currentPrice * 100 / dailyAggregates.takeRight(62).head.c

  lazy val perfQ2: Double = currentPrice * 100 / dailyAggregates.takeRight(125).head.c

  lazy val perfQ3: Double = currentPrice * 100 / dailyAggregates.takeRight(185).head.c

  lazy val perfQ4: Double = currentPrice * 100 / dailyAggregates.takeRight(250).head.c

  def pctChange(days: Int): Double = {
    val lastPrice = dailyAggregates.takeRight(days).last.c
    val firstPrice = dailyAggregates.takeRight(days + 1).head.c
    100 * (lastPrice - firstPrice) / firstPrice
  }

  def minClose(days: Int): Double = {
    dailyAggregates.takeRight(days).map(_.c).min
  }

  def maxClose(days: Int): Double = {
    dailyAggregates.takeRight(days).map(_.c).max
  }

  def avgVolume(days: Int): Double = {
    val volumes = dailyAggregates.takeRight(days).map(_.v)
    volumes.sum / volumes.length
  }

  def historicalSnapshot(upTo: java.time.LocalDate): Stock = {
    copy(dailyAggregates = dailyAggregates.filter(d => d.t.isBefore(upTo) || d.t.isEqual(upTo)))
  }
}
