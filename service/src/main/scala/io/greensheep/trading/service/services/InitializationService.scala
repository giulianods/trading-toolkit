package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.core.types.StockDescription
import io.greensheep.trading.dataservice.{MongoDataRepo, StockUniverse}
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
        val end = LocalDate.now()
        for{
          _ <- ZIO.logInfo("First run - creating stock universe")
          _ <- QuotesServices.getDailySnapshotService(start, end)
          stockUniverse = StockUniverse(_id = "stock-universe",
            name = "stock-universe",
            dataSource = "polygon",
            lastUpdateQuotes = end,
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
          _ <- if(start.isBefore(end)) QuotesServices.getDailySnapshotService(start, end) else ZIO.unit
          _ <- MongoDataRepo.saveStockUniverse(stockUniverse.copy(lastUpdateQuotes = end, symbols = usCommonStocks))
        } yield ()
      }
    } yield ()
  }

  private def rectifySplits: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = {
    for{
      _ <- ZIO.logInfo("Rectifying split data")
      universe <- MongoDataRepo.getStockUniverse
      _ <- QuotesServices.rectifySplits(universe.lastUpdateSplits.minusDays(7), LocalDate.now())

    } yield ()
  }

  //TODO add initialization for other services, see what can be done in parallel
  def initializeApp(): ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] =  {
    for {
      _ <- InitializeStockUniverse
      //_ <- InitializeDescriptions
      //_ <- initializeQuotes()
      //_ <- rectifySplits
      //_ <- ScreenerServices.ScreenerTask
    } yield ()
  }

}
