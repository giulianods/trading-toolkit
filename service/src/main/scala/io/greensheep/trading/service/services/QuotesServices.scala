package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.dataservice.{MongoDataRepo, MongoDbDataRepo, PolygonBridge, StockUniverse}
import zio.*
import zio.stream.ZStream

import scala.jdk.CollectionConverters.*
import java.time.LocalDate
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.language.postfixOps
import scala.util.Try

object QuotesServices {

  implicit val WriteExecutionContext: ExecutionContextExecutor = ExecutionContext.global

  val SaveBatchSize = 10

  private def datesBetween(startDate: LocalDate, endDate: LocalDate) = startDate.datesUntil(endDate).iterator().asScala.toSeq

  val TickersUsCommonStocks: ZIO[PolygonAsyncApi, Throwable, Seq[String]] = {
    ZIO.service[PolygonAsyncApi].flatMap(_.getTickers.runCollect.map(_.map(_.ticker)))
  }

  val DailyQuotesRetrieval: ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = {
    for {
      _ <- ZIO.logInfo("Stock universe found retrieving quotes from the last update")
      stockUniverse <- MongoDataRepo.getStockUniverse
      start = stockUniverse.lastUpdateQuotes.minusDays(1)
      end = LocalDate.now().minusDays(1)
      _ <- if (start.isBefore(end)) QuotesServices.getDailySnapshotService(start, end) else ZIO.unit
      _ <- MongoDataRepo.saveStockUniverse(stockUniverse.copy(lastUpdateQuotes = end))
    } yield ()
  }

  def getDailySnapshotService(start: LocalDate, end: LocalDate): ZIO[MongoDatabase & PolygonAsyncApi, Throwable, Unit] = ZIO.logSpan("get-daily-quotes") {
    for {
      _ <- ZIO.logInfo(s"Getting daily snapshots from $start to $end and saving them every $SaveBatchSize days")
      remoteApi <- ZIO.service[PolygonAsyncApi]
      datesToRetrieve = datesBetween(start, end)
      _ <- ZIO.cond(datesToRetrieve.nonEmpty, (), new IllegalArgumentException("No dates to retrieve"))
      _ <- ZIO.logInfo(s"Retrieving daily snapshots from ${datesToRetrieve.head} to ${datesToRetrieve.last}")
      dateStream = ZStream.fromIterable(datesToRetrieve)
      aggregateMaps = dateStream mapZIO { ld =>
        for {
          r <- remoteApi.getGroupedDaily(ld)
          _ <- ZIO.logInfo(s"Retrieved ${r.size} records for date $ld")
        } yield r
      } map(_.map(x => x.T.getOrElse("?") -> x).toMap)

      result = aggregateMaps.grouped(SaveBatchSize).map(_.flatMap(_.toSeq).groupBy(_._1).transform((_,v) => v.map(_._2))) mapZIO {
        date =>
          val results = date.map {
            case (ticker, aggregates) =>
              MongoDataRepo.updateDailyQuotes(ticker, aggregates.map(PolygonBridge.aggregateToQuote))
          }
          ZIO.collectAll(results).flatMap(i => ZIO.logInfo(s"Saved to MongoDB ${i.size} records"))
      }
      _ <- result.runCollect
    } yield ()
  }




}
