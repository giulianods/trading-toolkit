package io.greensheep.trading.core.screens

import io.greensheep.trading.core.types.{ScreenStats, Stock, StockMarket}
import zio.ZIO

object TrendScreens {


  val SEPA: ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.serviceWith[StockMarket] {
      stockMarket =>
        stockMarket.stocks.values.toSeq filter {
          stock =>
            stock.currentPrice > stock.ma50 &&
              stock.ma50 > stock.ma150 &&
              stock.ma150 > stock.ma200 &&
              stock.currentPrice > (stock.high52Week * 0.75) &&
              stock.currentPrice > (stock.low52Week * 1.3) &&
              stockMarket.ibdRelativeStrengthRanking(stock.symbol) > 70.0
        } sortBy (s => (100 - stockMarket.ibdRelativeStrengthRanking(s.symbol))) map {
          stock =>
            (stock, ScreenStats(
              ticker = stock.symbol,
              stats = Map(
                "currentPrice" -> f"${stock.currentPrice}%.2f",
                "ma50" -> f"${stock.ma50}%.2f",
                "ma150" -> f"${stock.ma150}%.2f",
                "ma200" -> f"${stock.ma200}%.2f",
                "52WeekHigh" -> f"${stock.high52Week}%.2f",
                "52WeekLow" -> f"${stock.low52Week}%.2f",
                "IBD strength" -> f"${stockMarket.ibdRelativeStrength(stock.symbol)}%.2f",
                "IBD rank" -> f"${stockMarket.ibdRelativeStrengthRanking(stock.symbol)}%.2f",
                "perf1q" -> f"${stock.perfQ1}%.2f",
                "perf2q" -> f"${stock.perfQ2}%.2f",
                "perf3q" -> f"${stock.perfQ3}%.2f",
                "perf4q" -> f"${stock.perfQ4}%.2f"
              )
            ))
        }

    }
  }

  val SevenPctUp: ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock => stock.performanceDays(5) > 7.0
    } sortBy (s => s.performanceDays(5))
    ) map { stocks =>
      stocks.map { stock =>
        (stock, ScreenStats(
          ticker = stock.symbol,
          stats = Map(
            "performanceDays(5)" -> f"${stock.performanceDays(5)}%.2f",
            "performanceDays(20)" -> f"${stock.performanceDays(20)}%.2f",
            "performanceDays(50)" -> f"${stock.performanceDays(50)}%.2f",
            "performanceDays(200)" -> f"${stock.performanceDays(200)}%.2f"
          )
        ))
      }
    }
  }

  val SevenPctDown: ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock => stock.performanceDays(5) < -7.0
    } sortBy (s => (-s.performanceDays(5)))
    ) map {
      stocks =>
        stocks.map { stock =>
          (stock, ScreenStats(
            ticker = stock.symbol,
            stats = Map(
              "performanceDays(5)" -> f"${stock.performanceDays(5)}%.2f",
              "performanceDays(20)" -> f"${stock.performanceDays(20)}%.2f",
              "performanceDays(50)" -> f"${stock.performanceDays(50)}%.2f",
              "performanceDays(200)" -> f"${stock.performanceDays(200)}%.2f"
            )
          ))
        }
    }
  }
}
