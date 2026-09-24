package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.{Stock, StockMarket}

class SepaScreens(stockMarket: StockMarket) {
  def screen(): Seq[Stock] = {
    val candidates = stockMarket.stocks.values.filter(TrendScreenQuality.eligible)
    val eligibleMarket = StockMarket(candidates.map(s => s.symbol -> s).toMap)
    candidates
      .filter { stock =>
        stock.currentPrice > stock.ma50 &&
          stock.ma50 > stock.ma150 &&
          stock.ma150 > stock.ma200 &&
          stock.currentPrice > (stock.high52Week * 0.75) &&
          stock.currentPrice > (stock.low52Week * 1.3) &&
          eligibleMarket.ibdRelativeStrengthRanking.getOrElse(stock.symbol, 0.0) > 70.0
      }
      .toSeq
      .sortBy(s => eligibleMarket.ibdRelativeStrengthRanking.getOrElse(s.symbol, 0.0))
  }

}
