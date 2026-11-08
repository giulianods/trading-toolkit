package io.greensheep.trading.core.types

import java.time.LocalDate

case class StockMarket(stocks: Map[String, Stock], issuerByTicker: Map[String, String] = Map.empty) {



  lazy val ibdRelativeStrengthRanking: Map[String, Double] = {
    val length = ibdRelativeStrength.size.toDouble
    if (length == 0) Map.empty
    else
      ibdRelativeStrength.toSeq.sortBy(_._2).zipWithIndex.map(x => x._1._1 -> 100 * x._2 / length).toMap
  }

  lazy val ibdRelativeStrength: Map[String, Double] = {
    stocks.values.iterator
      .filter(_.dailyAggregates.nonEmpty)
      .map { s =>
        val ibdStrength = (2 * s.perfQ1 + s.perfQ2 + s.perfQ3 + s.perfQ4) / 5
        s.symbol -> ibdStrength
      }
      .filter { case (_, strength) => strength.isFinite }
      .toMap
  }

  def currentDate: LocalDate = stocks.values.head.dailyAggregates.last.t


  def historicalSnapshot(upTo: java.time.LocalDate): StockMarket = {
    val snapshot = stocks.view.mapValues(_.historicalSnapshot(upTo))
      .filter { case (_, stock) => stock.dailyAggregates.nonEmpty }.toMap
    if (issuerByTicker.isEmpty) StockMarket(snapshot)
    else {
      val (listed, ungrouped) = snapshot.partition { case (ticker, _) => issuerByTicker.contains(ticker) }
      val representatives = listed.valuesIterator.toSeq.groupBy(stock => issuerByTicker(stock.symbol)).valuesIterator
        .map(_.maxBy { stock =>
          val recent = stock.dailyAggregates.takeRight(20)
          val averageTurnover = recent.iterator.map(bar => bar.c * bar.v).sum / recent.size
          (recent.last.t, averageTurnover, stock.symbol)
        })
      StockMarket(ungrouped ++ representatives.map(stock => stock.symbol -> stock))
    }
  }
}
