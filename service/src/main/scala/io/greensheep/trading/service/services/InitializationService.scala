package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.core.screens.BreadthIndicators as MarketBreadth
import io.greensheep.trading.core.types.StockDescription
import io.greensheep.trading.dataservice.{MongoDataRepo, PolygonBridge, StockUniverse}
import zio.ZIO

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object InitializationService {

  implicit val WriteExecutionContext: ExecutionContextExecutor = ExecutionContext.global

  val InitializeStockUniverse: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = ZIO.logSpan("initialize-stock-universe") {
    for {
      _ <- ZIO.logInfo("Initializing stock universe")
      alreadyRan <- MongoDataRepo.hasStockUniverse
      usCommonStocks <- QuotesServices.TickersUsCommonStocks  //TODO diff with existing symbols
      _ <- if(!alreadyRan) {
        for{
          _ <- ZIO.logInfo("First run - creating stock universe")
          stockUniverse = StockUniverse(_id = "stock-universe",
            name = "stock-universe",
            dataSource = "polygon",
            lastUpdateQuotes = LocalDate.now().minusMonths(23),
            lastUpdateFinancials = LocalDate.of(1970,1,1),
            timeZone = "America/New_York",
            symbols = usCommonStocks)
          _ <- MongoDataRepo.saveStockUniverse(stockUniverse)
        } yield ()
      } else {
        for {
          stockUniverse <- MongoDataRepo.getStockUniverse
          existingSymbols = stockUniverse.symbols
          missingSymbols = usCommonStocks.filterNot(existingSymbols.contains)
          _ <- if(missingSymbols.nonEmpty) {
            ZIO.logInfo(s"Missing symbols: ${missingSymbols.mkString(", ")}")
          } else {
            ZIO.unit
          }
          _ <- MongoDataRepo.saveStockUniverse(stockUniverse.copy(symbols = usCommonStocks))
        } yield ()
      }

    } yield ()
  }

  val InitializeDescriptions: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = ZIO.logSpan("initialize-descriptions") {
    for{
      universe <- MongoDataRepo.getStockUniverse
      remoteApi <- ZIO.service[PolygonAsyncApi]
      existing <- MongoDataRepo.getAllStockDescriptions.map(_.map(_.symbol).toSet)
      collectors  = universe.symbols.filterNot(existing.contains).map(s => remoteApi.getTickerDetails(s).map(td =>
      StockDescription(
        symbol = td.ticker,
        companyName = td.name,
        ipoDate = td.listDate.map(LocalDate.parse).getOrElse(LocalDate.now()),
        primaryExchange = td.primaryExchange,
        description = td.description.getOrElse(""),
        totalEmployees = td.totalEmployees.getOrElse(-1),
        sicCode = td.sicCode.getOrElse(""),
        sicDescription = td.sicDescription.getOrElse(""),
        marketCap = td.marketCap.map(_.toLong).getOrElse(-1L),
        weightedSharesOutstanding = td.weightedSharesOutstanding.getOrElse(-1L)
      )).flatMap(MongoDataRepo.saveStockDescription))
      results <- ZIO.foreach(collectors)(_.either).map(_.foldLeft((Seq.empty[Throwable], Seq.empty[StockDescription])) {
        case ((ts, xs), Left(t))  => (ts :+ t, xs)
        case ((ts, xs), Right(x)) => (ts, xs :+ x)
      })
      _ <- ZIO.logInfo(s"Failed to save ${results._1.size} descriptions")
      _ <- ZIO.logInfo(s"Updated ${results._2.size} descriptions")
    } yield ()
  }


  private def initializeQuotes(): ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = ZIO.logSpan("initialize-quotes") {
    for {
      _ <- ZIO.logInfo("Initializing quotes")
      alreadyRan <- MongoDataRepo.hasStockUniverse
      usCommonStocks <- QuotesServices.TickersUsCommonStocks  //TODO diff with existing symbols
      _ <- if(!alreadyRan) {
        val start = LocalDate.now().minusMonths(23)
        val end = LocalDate.now().minusDays(1)
        for{
          _ <- ZIO.logInfo("First run - creating stock universe")
          lastWritten <- QuotesServices.getDailySnapshotService(start, end)
          stockUniverse = StockUniverse(_id = "stock-universe",
            name = "stock-universe",
            dataSource = "polygon",
            lastUpdateQuotes = lastWritten.getOrElse(end),
            lastUpdateFinancials = LocalDate.of(1970,1,1),
            timeZone = "America/New_York",
            symbols = usCommonStocks)
          _ <- MongoDataRepo.saveStockUniverse(stockUniverse)
        } yield ()
      } else {
        for {
          _ <- ZIO.logInfo("Stock universe found retrieving quotes from the last update")
          stockUniverse <- MongoDataRepo.getStockUniverse
          start = stockUniverse.lastUpdateQuotes
          end = LocalDate.now().minusDays(1)
          lastWritten <- QuotesServices.getDailySnapshotService(start, end)
          _ <- MongoDataRepo.saveStockUniverse(
            stockUniverse.copy(
              lastUpdateQuotes = lastWritten.getOrElse(stockUniverse.lastUpdateQuotes),
              symbols = usCommonStocks
            )
          )
        } yield ()
      }
    } yield ()
  }

  private def initializeHistoricalWatchlists(): ZIO[MongoDatabase, Throwable, Unit] = {
    val end = LocalDate.now().minusDays(1)
    val start = end.minusMonths(3)
    val dates = QuotesServices.datesBetweenInclusive(start, end)

    for {
      _ <- ZIO.logInfo(s"Initializing default watchlists from $start to $end")
      market <- ScreenerServices.LoadQuotes
      _ <- ZIO.foreachDiscard(dates) { date =>
        for {
          alreadyExists <- MongoDataRepo.hasWatchlistsForDate(date)
          _ <- if (alreadyExists) {
            ZIO.logInfo(s"Watchlists already exist for date $date, skipping initialization")
          } else {
            ScreenerServices
              .buildScreensForMarket(date, market)
              .tapBoth(
                err => ZIO.logError(s"Failed to build watchlists for date $date: ${err.getMessage}"),
                _ => ZIO.logInfo(s"Saved watchlists for date $date")
              )
              .ignore
          }
        } yield ()
      }
    } yield ()
  }

  private def initializeMarketBreadthHistory(): ZIO[MongoDatabase, Throwable, Unit] = {
    val end = LocalDate.now().minusDays(1)
    val start = end.minusMonths(3)
    val dates = QuotesServices.datesBetweenInclusive(start, end)

    for {
      _ <- ZIO.logInfo(s"Initializing market breadth indicators from $start to $end")
      stockMarket <- ScreenerServices.LoadQuotes
      _ <- ZIO.foreachDiscard(dates)(date =>
        for {
          alreadyExists <- MongoDataRepo.hasMarketBreadthForDate(date)
          _ <- if (alreadyExists) {
            ZIO.logInfo(s"Market breadth indicators already exist for date $date, skipping initialization")
          } else {
            val snapshot = stockMarket.historicalSnapshot(date)
            snapshot.stocks.get("AAPL") match {
              case Some(_) =>
                val indicators = MarketBreadth.fromStockMarket(snapshot).copy(date = date)
                MongoDataRepo
                  .saveMarketBreadth(indicators)
                  .tapError(err => ZIO.logError(s"Failed to save market breadth indicators for date $date: ${err.getMessage}"))
                  .flatMap(_ => ZIO.logInfo(s"Saved market breadth indicators for date $date"))
              case None =>
                ZIO.logWarning(s"Skipping market breadth initialization for date $date because benchmark data is missing")
            }
          }
        } yield ()
      )
    } yield ()
  }

  private def quotesSanityCheck: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Seq[(String, String)]] = {
    for{
      _ <- ZIO.logInfo("Performing quotes sanity check")
      universe <- MongoDataRepo.getStockUniverse
      results <- ZIO.foreachPar(universe.symbols) { ticker =>
        QuotesServices.sanityCheck(ticker).either.map(res => (ticker, res))
      }.withParallelism(8)
      errors = results.collect { case (t, Left(err)) => (t, err) }
    } yield errors
  }

  private def resetQuotes(ticker: String): ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = {
    for{
      _ <- ZIO.logInfo(s"Resetting quotes for $ticker")
      _ <- MongoDataRepo.clearQuotes(ticker)
      //Retrieve quotes from Polygon
      remoteApi <- ZIO.service[PolygonAsyncApi]
      allQuotes <- remoteApi.getAggregates(ticker, LocalDate.now().minusMonths(23))
      quotes = allQuotes.map(PolygonBridge.aggregateToQuote)
      //Save quotes to MongoDB
      _ <- MongoDataRepo.updateDailyQuotes(ticker, quotes)
      _ <- ZIO.logInfo(s"Reset quotes for $ticker completed, total quotes: ${quotes.size}")
    } yield ()
  }


  private def repairQuotes: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = {
    for{
      _ <- ZIO.logInfo("Repairing quote data")
      faults <- quotesSanityCheck
      _ <- ZIO.logInfo(s"Faulty tickers: ${faults.size}")
      _ <- ZIO.foreachDiscard(faults)(fault => ZIO.logInfo(s"$fault"))
      faultyTickers = faults.map(_._1)
      _ <- ZIO.foreachPar(faultyTickers)(resetQuotes).withParallelism(4)
    } yield ()
  }

  private def rectifySplits: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = {
    for{
      _ <- ZIO.logInfo("Rectifying split data")
      universe <- MongoDataRepo.getStockUniverse
      _ <- QuotesServices.rectifySplits(universe.lastUpdateSplits.minusDays(7), LocalDate.now().minusDays(1))

    } yield ()
  }

  //TODO add initialization for other services, see what can be done in parallel
  def initializeApp(): ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] =  {
    for {
      _ <- InitializeStockUniverse
      _ <- initializeQuotes()
      _ <- rectifySplits
      _ <- repairQuotes
      _ <- InitializeDescriptions
        .catchAll(error => ZIO.logWarning(s"Description initialization skipped: ${error.getMessage}"))
        .forkDaemon
      _ <- initializeHistoricalWatchlists()
      _ <- initializeMarketBreadthHistory()
      _ <- ScreenerServices.ScreenerTask
    } yield ()
  }

}
