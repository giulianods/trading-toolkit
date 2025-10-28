package io.greensheep.trading.core.types

import java.time.LocalDate

case class StockMarket(stocks: Map[String, Stock]) {



  lazy val ibdRelativeStrengthRanking: Map[String, Double] = {
    val ibdStrength = stocks.values map {
      s =>
        val ibdStrength = (2 * s.perfQ1 + s.perfQ2 + s.perfQ3 + s.perfQ4) / 5
        s.symbol -> ibdStrength
    }
    val length = ibdRelativeStrength.size.toDouble
    ibdStrength.toSeq.sortBy(_._2).zipWithIndex.map(x => x._1._1 -> 100 * x._2/length).toMap
  }

  lazy val ibdRelativeStrength: Map[String, Double] = {
    stocks.values map {
      s =>
        val ibdStrength = (2 * s.perfQ1 + s.perfQ2 + s.perfQ3 + s.perfQ4) / 5
        s.symbol -> ibdStrength
    }
  }.toMap

  def currentDate: LocalDate = stocks.values.head.dailyAggregates.last.t


  def historicalSnapshot(upTo: java.time.LocalDate): StockMarket = {
    StockMarket(
      stocks.map {
        case (k, v) => k -> v.historicalSnapshot(upTo)
      } filter(_._2.dailyAggregates.nonEmpty)
    )
  }
}
