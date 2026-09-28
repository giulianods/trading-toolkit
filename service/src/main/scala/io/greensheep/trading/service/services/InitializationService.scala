package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.trading.core.screens.BreadthIndicators as MarketBreadth
import io.greensheep.trading.core.types.{MarketDefinition, StockDescription}
import io.greensheep.trading.dataservice.{MongoDataRepo, StockUniverse}
import zio.ZIO

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object InitializationService {

  implicit val WriteExecutionContext: ExecutionContextExecutor = ExecutionContext.global

  def initializeStockUniverse(market: MarketDefinition): ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    ZIO.logSpan(s"initialize-stock-universe:${market.id.id}") {
    for {
      _ <- ZIO.logInfo(s"Initializing stock universe for ${market.id.id}")
      alreadyRan <- MongoDataRepo.hasStockUniverse
      usCommonStocks <- QuotesServices.TickersUsCommonStocks  //TODO diff with existing symbols
      _ <- if(!alreadyRan) {
        for{
          _ <- ZIO.logInfo("First run - creating stock universe")
          stockUniverse = StockUniverse(_id = "stock-universe",
            name = "stock-universe",
            dataSource = market.dataSource,
            lastUpdateQuotes = LocalDate.now().minusMonths(23),
            lastUpdateFinancials = LocalDate.of(1970,1,1),
            timeZone = market.timeZone,
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

  val InitializeDescriptions: ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] = ZIO.logSpan("initialize-descriptions") {
    for{
      universe <- MongoDataRepo.getStockUniverse
      remoteApi <- ZIO.service[MassiveAsyncApi]
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


  private def initializeQuotes(market: MarketDefinition): ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    ZIO.logSpan(s"initialize-quotes:${market.id.id}") {
    for {
      _ <- ZIO.logInfo(s"Initializing quotes for ${market.id.id}")
      alreadyRan <- MongoDataRepo.hasStockUniverse
      _ <- if(!alreadyRan) {
        for {
          usCommonStocks <- QuotesServices.TickersUsCommonStocks
          start = LocalDate.now().minusMonths(23)
          end = LocalDate.now().minusDays(1)
          _ <- ZIO.logInfo("First run - creating stock universe")
          lastWritten <- QuotesServices.getDailySnapshotService(start, end)
          stockUniverse = StockUniverse(_id = "stock-universe",
            name = "stock-universe",
            dataSource = market.dataSource,
            lastUpdateQuotes = lastWritten.getOrElse(end),
            lastUpdateFinancials = LocalDate.of(1970,1,1),
            timeZone = market.timeZone,
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
              lastUpdateQuotes = lastWritten.getOrElse(stockUniverse.lastUpdateQuotes)
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
            if (snapshot.stocks.nonEmpty) {
              val indicators = MarketBreadth.fromStockMarket(snapshot).copy(date = date)
              MongoDataRepo
                .saveMarketBreadth(indicators)
                .tapError(err => ZIO.logError(s"Failed to save market breadth indicators for date $date: ${err.getMessage}"))
                .flatMap(_ => ZIO.logInfo(s"Saved market breadth indicators for date $date"))
            } else {
              ZIO.logWarning(s"Skipping market breadth initialization for date $date because the snapshot has no stocks")
            }
          }
        } yield ()
      )
    } yield ()
  }

  private def rectifySplits: ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] = {
    for{
      _ <- ZIO.logInfo("Rectifying split data")
      universe <- MongoDataRepo.getStockUniverse
      _ <- QuotesServices.rectifySplits(universe.lastUpdateSplits.minusDays(7), LocalDate.now().minusDays(1))

    } yield ()
  }

  //TODO add initialization for other services, see what can be done in parallel
  def initializeApp(market: MarketDefinition): ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =  {
    for {
      _ <- ZIO.logInfo(s"Initializing market ${market.id.id}")
      // Catch quotes up before refreshing the ticker list. A hung ticker call must not block prices.
      _ <- initializeQuotes(market)
      _ <- initializeStockUniverse(market).catchAll { error =>
        ZIO.logError(s"Stock universe refresh failed, continuing with stored symbols: ${error.getMessage}")
      }
      _ <- rectifySplits
      _ <- QuotesServices.nightlyHygiene
      _ <- InitializeDescriptions
        .catchAll(error => ZIO.logWarning(s"Description initialization skipped: ${error.getMessage}"))
        .forkDaemon
      _ <- initializeHistoricalWatchlists()
      _ <- initializeMarketBreadthHistory()
      _ <- ScreenerServices.ScreenerTask
    } yield ()
  }

}
