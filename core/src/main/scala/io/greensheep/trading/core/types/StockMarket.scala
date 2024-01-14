package io.greensheep.trading.core.types

case class StockMarket(stocks: Map[String, Stock]) {

  lazy val ibdRelativeStrengthRanking: Map[String, Double] = {
    val ibdStrength = stocks.values map {
      s =>
        val ibdStrength = (2 * s.perfQ1 + s.perfQ2 + s.perfQ3 + s.perfQ4) / 5
        s.symbol -> ibdStrength
    }
    val length = ibdStrength.size.toDouble
    ibdStrength.toSeq.sortBy(_._2).zipWithIndex.map(x => x._1._1 -> 100 * x._2/length).toMap
  }

}
