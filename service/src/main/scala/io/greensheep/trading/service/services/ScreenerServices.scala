package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.screens.{BreadthIndicators as MarketBreadth, TrendScreens}
import io.greensheep.trading.core.types.{DailyAggregate, ScreenStats, Stock, StockMarket, Watchlist}
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.MongoDataRepo
import zio.{Chunk, Clock, ZIO, ZLayer}
import zio.json.{DeriveJsonEncoder, JsonEncoder}

import java.time.LocalDate

object ScreenerServices {

  final case class ScreenOption(id: String, label: String)
  object ScreenOption {
    implicit val encoder: JsonEncoder[ScreenOption] = DeriveJsonEncoder.gen[ScreenOption]
  }

  private val catalog = Seq(
    ScreenOption("SEPA", "SEPA") -> TrendScreens.SEPA,
    ScreenOption("VCP", "VCP") -> TrendScreens.VolatilityContractionPattern,
    ScreenOption("7_PCT_UP", "7%UP") -> TrendScreens.SevenPctUp,
    ScreenOption("7_PCT_DOWN", "7% Down") -> TrendScreens.SevenPctDown,
    ScreenOption("NEW_52_WEEK_HIGH", "New 52-Week High") -> TrendScreens.New52WeekHigh,
    ScreenOption("NEW_52_WEEK_LOW", "New 52-Week Low") -> TrendScreens.New52WeekLow,
    ScreenOption("NEW_IPOS", "New IPOs") -> TrendScreens.NewIPOs
  )

  private lazy val enabledScreens = LocalConfig.getList("ttk.screens").fold(catalog) { names =>
    require(names.nonEmpty, "ttk.screens must list at least one screen")
    names.map { name =>
      catalog.find { case (option, _) =>
        option.id.equalsIgnoreCase(name) || option.label.equalsIgnoreCase(name)
      }.getOrElse {
        throw new IllegalArgumentException(s"Unknown screen '$name' in ttk.screens. Valid IDs: ${catalog.map(_._1.id).mkString(", ")}")
      }
    }.distinctBy(_._1.id)
  }

  def screenOptions: Seq[ScreenOption] = enabledScreens.map(_._1)

  val LoadQuotes: ZIO[MongoDatabase, Throwable, StockMarket] = for {
    universe <- MongoDataRepo.getStockUniverse
    allQuotes <- ZIO
      .foreachPar(universe.symbols) { symbol =>
        MongoDataRepo.getPriceData(symbol).map(price => symbol -> price).either
      }
      .withParallelism(8)
    errors = allQuotes.collect { case Left(error) => error }
    loaded = allQuotes.collect { case Right(pair) => pair }
    _ <- ZIO.logError(s"Errors while loading quotes: ${errors.size}").when(errors.nonEmpty)
    stocks = loaded.filter(_._2.quotes.nonEmpty).map { case (symbol, data) =>
      Stock(
        symbol = symbol,
        companyName = symbol,
        description = "",
        primaryExchange = "",
        ipoDate = LocalDate.now(),
        dailyAggregates = Chunk.fromIterable(data.quotes.map(DailyAggregate.apply))
      )
    }
  } yield StockMarket(stocks.map(stock => stock.symbol -> stock).toMap, universe.issuerByTicker.getOrElse(Map.empty))

  def buildWatchlist(name: String, date: LocalDate)(stocks: Seq[(Stock, ScreenStats)]): ZIO[Any, Throwable, Watchlist] =
    Clock.instant.map { generatedAt =>
      Watchlist(
        day = date,
        screenName = name,
        tickers = stocks.map(_._1.symbol),
        stats = stocks.map(_._2),
        generatedAt = Some(generatedAt)
      )
    }

  def buildScreens(date: LocalDate): ZIO[StockMarket, Throwable, Seq[Watchlist]] =
    ZIO.foreach(enabledScreens) { case (option, screen) => screen.flatMap(buildWatchlist(option.id, date)) }

  def getScreensForDate(date: LocalDate): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    val stockMarket = ZLayer.fromZIO(LoadQuotes.map(_.historicalSnapshot(date)))
    buildScreens(date).provideLayer(stockMarket)
  }

  def buildScreensForDate(date: LocalDate): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] =
    for {
      _ <- ZIO.logInfo(s"Building screens for date: $date")
      watchlists <- getScreensForDate(date)
      _ <- ZIO.foreachDiscard(watchlists)(MongoDataRepo.saveWatchlist)
      _ <- ZIO.logInfo(s"Saved ${watchlists.size} watchlists for date: $date")
    } yield watchlists

  def buildScreensForMarket(date: LocalDate, market: StockMarket): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    val snapshot = market.historicalSnapshot(date)
    for {
      watchlists <- buildScreens(date).provideLayer(ZLayer.succeed(snapshot))
      _ <- ZIO.foreachDiscard(watchlists)(MongoDataRepo.saveWatchlist)
    } yield watchlists
  }

  val ScreenerTask: ZIO[MongoDatabase, Throwable, Unit] =
    ZIO.suspendSucceed(runScreener(LocalDate.now().minusDays(1)))

  val latestSessionScreenerTask: ZIO[MongoDatabase, Throwable, Unit] =
    MongoDataRepo.getStockUniverse.flatMap(universe => runScreener(universe.lastUpdateQuotes, requireSession = true))

  private def runScreener(asOf: LocalDate, requireSession: Boolean = false): ZIO[MongoDatabase, Throwable, Unit] =
      ZIO.logSpan("screener-task") {
        for {
          _ <- ZIO.logInfo(s"Running screener task for $asOf")
          market <- LoadQuotes
          snapshot = market.historicalSnapshot(asOf)
          hasSession = snapshot.stocks.valuesIterator.exists(_.dailyAggregates.last.t == asOf)
          _ <- (for {
            watchlists <- buildScreens(asOf).provideLayer(ZLayer.succeed(snapshot))
            _ <- ZIO.foreachDiscard(watchlists)(MongoDataRepo.saveWatchlist)
            _ <- ZIO.logInfo(s"Saved ${watchlists.size} watchlists")
          } yield ()).when(!requireSession || hasSession)
          _ <- if (snapshot.stocks.nonEmpty && (!requireSession || hasSession)) {
            val indicators = MarketBreadth.fromStockMarket(snapshot).copy(date = asOf)
            MongoDataRepo
              .saveMarketBreadth(indicators)
              .catchAll(error => ZIO.logError(s"Failed to save market breadth: ${error.getMessage}"))
          } else {
            ZIO.logWarning(s"Skipping market breadth because there is no usable snapshot for $asOf")
          }
        } yield ()
      }
}
