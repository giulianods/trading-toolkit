package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.{Stock, StockMarket}
import zio.ZIO

object TrendScreens {


  val SEPA: ZIO[StockMarket, Throwable, Seq[Stock]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock =>
        stock.currentPrice > stock.ma50 &&
          stock.ma50 > stock.ma150 &&
          stock.ma150 > stock.ma200 &&
          stock.currentPrice > (stock.high52Week * 0.75) &&
          stock.currentPrice > (stock.low52Week * 1.3) &&
          stockMarket.ibdRelativeStrengthRanking(stock.symbol) > 70.0
    } sortBy (s => (100 - stockMarket.ibdRelativeStrengthRanking(s.symbol)))
    )
  }

  val SevenPctUp: ZIO[StockMarket, Throwable, Seq[Stock]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock => stock.performanceDays(5) > 7.0
    } sortBy (s => s.performanceDays(5))
    )
  }

  val SevenPctDown: ZIO[StockMarket, Throwable, Seq[Stock]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock => stock.performanceDays(5) < -7.0
    } sortBy (s => (-s.performanceDays(5)))
    )
  }
}
