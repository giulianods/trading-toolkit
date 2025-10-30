package io.greensheep.trading.core.screens

import io.greensheep.trading.core.screens.utils.VCPDebug.DebugResult
import io.greensheep.trading.core.screens.utils.{VCPDebug, VCPDetector, VCPParams}
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

  val New52WeekHigh: ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock => stock.high52Week <= stock.dailyAggregates.last.h
    } sortBy (s => 100 - s.perfQ4)
    ) map {
      stocks =>
        stocks.map { stock =>
          (stock, ScreenStats(
            ticker = stock.symbol,
            stats = Map(
              "high52Week" -> f"${stock.high52Week}%.2f",
              "annualPerf" -> f"${stock.perfQ4}%.2f",
              "low52Week" -> f"${stock.low52Week}%.2f"
            )
          ))
        }
    }
  }

  val New52WeekLow: ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock => stock.low52Week >= stock.dailyAggregates.last.l
    } sortBy (s => s.perfQ4)
    ) map {
      stocks =>
        stocks.map { stock =>
          (stock, ScreenStats(
            ticker = stock.symbol,
            stats = Map(
              "low52Week" -> f"${stock.low52Week}%.2f",
              "annualPerf" -> f"${stock.perfQ4}%.2f",
              "high52Week" -> f"${stock.high52Week}%.2f"
            )
          ))
        }
    }
  }

  val NewIPOs : ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq filter {
      stock => stock.dailyAggregates.size < 20
    } sortBy (s => s.perfQ4)
    ) map {
      stocks =>
        stocks.map { stock =>
          (stock, ScreenStats(
            ticker = stock.symbol,
            stats = Map(
              "ipoDate" -> stock.ipoDate.toString
            )
          ))
        }
    }
  }
  

  def buildVcpScreen(params: VCPParams) : ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq.map(s => (s, VCPDetector.detect(s.dailyAggregates, params)))
      .filter(x => x._2.isDefined).map(kv => (kv._1, kv._2.get)).sortBy(_._2.score) map { case (stock, vcpHit) =>
      (stock, ScreenStats(
        ticker = "stock.symbol",
        stats = Map(
          "score" -> f"${vcpHit.score}%.2f",
          "pivotDate" -> vcpHit.pivotDate.toString,
          "numContractions" -> vcpHit.contractions.size.toString,
          "uptrendOk" -> vcpHit.uptrendOk.toString,
          "volumeTrendDown" -> vcpHit.volumeTrendDown.toString,
          "startDate" -> vcpHit.startDate.toString,
          "endDate" -> vcpHit.endDate.toString
        )
      ))

    })
  }

  val VolatilityContractionPattern: ZIO[StockMarket, Throwable, Seq[(Stock, ScreenStats)]] = {
    ZIO.service[StockMarket].map(stockMarket => stockMarket.stocks.values.toSeq.map(s => (s, VCPDetector.detect(s.dailyAggregates, VCPParams.Default)))
      .filter(x => x._2.isDefined).map(kv => (kv._1, kv._2.get)).sortBy(_._2.score) map { case (stock, vcpHit) =>
      (stock, ScreenStats(
        ticker = "stock.symbol",
        stats = Map(
          "score" -> f"${vcpHit.score}%.2f",
          "pivotDate" -> vcpHit.pivotDate.toString,
          "numContractions" -> vcpHit.contractions.size.toString,
          "uptrendOk" -> vcpHit.uptrendOk.toString,
          "volumeTrendDown" -> vcpHit.volumeTrendDown.toString,
          "startDate" -> vcpHit.startDate.toString,
          "endDate" -> vcpHit.endDate.toString
        )
      ))

    })
  }

  def debugVCP(params: VCPParams): ZIO[StockMarket, Throwable, Seq[DebugResult]] = {
    ZIO.service[StockMarket].map(sm => sm.stocks.values.toSeq.map(s => VCPDebug.detectWithReasons(s.symbol,s.dailyAggregates, params)))
  }

}
