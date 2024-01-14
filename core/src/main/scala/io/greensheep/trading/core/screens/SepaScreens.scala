package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.{Stock, StockMarket}

class SepaScreens(stockMarket: StockMarket) {
  def sepaScreens(): Seq[Stock] = {
    val filtered = stockMarket.stocks.values filter {
      stock =>
        stock.currentPrice > stock.ma50 &&
          stock.ma50 > stock.ma150 &&
          stock.ma150 > stock.ma200 &&
          stock.currentPrice > (stock.high52Week * 0.75) &&
          stock.currentPrice > (stock.low52Week * 1.3) &&
          stockMarket.ibdRelativeStrengthRanking(stock.symbol) > 70.0
    }
    filtered.toSeq.sortBy(s => stockMarket.ibdRelativeStrengthRanking(s.symbol))
  }

}
