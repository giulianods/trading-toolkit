package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.StockMarket
import zio.json.DeriveJsonEncoder
import zio.json.JsonDecoder
import zio.json.DeriveJsonDecoder
import zio.json.JsonEncoder
import java.time.LocalDate


object BreadthIndicators {

  def buildHistory(mkt: StockMarket): Seq[BreadthIndicators] = {
    val dates = mkt.stocks("AAPL").dailyAggregates.map(_.t).toSeq
    dates.map(date => fromStockMarket(mkt.historicalSnapshot(date)))
  }

  def buildHistory(mkt: StockMarket, noDays: Int): Seq[BreadthIndicators] = {
    val dates = mkt.stocks("AAPL").dailyAggregates.map(_.t).toSeq.takeRight(noDays)
    dates.map(date => fromStockMarket(mkt.historicalSnapshot(date)))
  }



  def fromStockMarket(mkt: StockMarket): BreadthIndicators = {
    val day_4pct_up = mkt.stocks.values.count(s => s.pctChange(1) >= 4.0
      && s.avgVolume(1) > 1000
      && (s.avgVolume(1) > s.avgVolume(2)))

    val day_4pct_down = mkt.stocks.values.count(s => s.pctChange(1) <= -4.0
      && s.avgVolume(1) > 1000
      && (s.avgVolume(1) > s.avgVolume(2)))

    val qt_25pct_up = mkt.stocks.values.count(s => s.currentPrice >= s.minClose(50) * 1.25
      && s.ma(50) * s.avgVolume(50) > 2500)

    val qt_25pct_down = mkt.stocks.values.count(s => s.currentPrice <= s.maxClose(50) * 0.75
      && s.ma(50) * s.avgVolume(50) > 2500)

    val monthly_25pct_up = mkt.stocks.values.count(s => s.currentPrice >= s.minClose(20) * 1.25
      && s.ma(20) * s.avgVolume(20) > 2500 && s.currentPrice > 5.0)

    val monthly_25pct_down = mkt.stocks.values.count(s => s.currentPrice <= s.maxClose(20) * 0.75
      && s.ma(20) * s.avgVolume(20) > 2500 && s.currentPrice > 5.0)

    val monthly_50pct_up = mkt.stocks.values.count(s => s.currentPrice >= s.minClose(20) * 1.5
      && s.ma(20) * s.avgVolume(20) > 2500 && s.currentPrice > 5.0)

    val monthly_50pct_down = mkt.stocks.values.count(s => s.currentPrice <= s.maxClose(20) * 0.5
      && s.ma(20) * s.avgVolume(20) > 2500 && s.currentPrice > 5.0)

    val bull_34_13 = mkt.stocks.values.count(s => s.currentPrice >= s.minClose(34) * 1.13 
      && s.ma(20) * s.avgVolume(20) > 2500 && s.currentPrice > 5.0)

    val bear_34_13 = mkt.stocks.values.count(s => s.currentPrice <= s.maxClose(34) * 0.87
      && s.ma(20) * s.avgVolume(20) > 2500 && s.currentPrice > 5.0)

    BreadthIndicators(
      mkt.currentDate,
      day_4pct_up,
      day_4pct_down,
      qt_25pct_up,
      qt_25pct_down,
      monthly_25pct_up,
      monthly_25pct_down,
      monthly_50pct_up,
      monthly_50pct_down,
      bull_34_13,
      bear_34_13
    )
  }

  implicit val Encoder: JsonEncoder[BreadthIndicators] = DeriveJsonEncoder.gen[BreadthIndicators]
  implicit val Decoder: JsonDecoder[BreadthIndicators] = DeriveJsonDecoder.gen[BreadthIndicators]


}


case class BreadthIndicators(
  date: LocalDate,
  day_4pct_up: Int,
  day_4pct_down: Int,
  qt_25pct_up: Int,
  qt_25pct_down: Int,
  monthly_25pct_up: Int,
  monthly_25pct_down: Int,
  monthly_50pct_up: Int,
  monthly_50pct_down: Int,
  bull_34_13: Int,
  bear_34_13: Int
) {

}
