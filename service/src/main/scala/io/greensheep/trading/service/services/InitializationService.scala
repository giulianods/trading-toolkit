package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.dataservice.{MongoDataRepo, StockUniverse}
import zio.ZIO

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object InitializationService {

  implicit val WriteExecutionContext: ExecutionContextExecutor = ExecutionContext.global


  private def initializeQuotes(): ZIO[MongoDatabase with PolygonAsyncApi, Throwable, Unit] = ZIO.logSpan("initialize-quotes") {
    for {
      _ <- ZIO.logInfo("Initializing quotes")
      alreadyRan <- MongoDataRepo.hasStockUniverse
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
            symbols = Seq.empty)
          _ <- MongoDataRepo.saveStockUniverse(stockUniverse)
        } yield ()
      } else {
        for {
          _ <- ZIO.logInfo("Stock universe found retrieving quotes from the last update")
          stockUniverse <- MongoDataRepo.getStockUniverse
          start = stockUniverse.lastUpdateQuotes
          end = LocalDate.now()
          _ <- if(start.isBefore(end)) QuotesServices.getDailySnapshotService(start, end) else ZIO.unit
          _ <- MongoDataRepo.saveStockUniverse(stockUniverse.copy(lastUpdateQuotes = end))
        } yield ()
      }

    } yield ()
  }

  //TODO add initialization for other services, see what can be done in parallel
  def initializeApp(): ZIO[MongoDatabase with PolygonAsyncApi, Throwable, Unit] =  {
    for {
      _ <- initializeQuotes()
    } yield ()
  }

}
