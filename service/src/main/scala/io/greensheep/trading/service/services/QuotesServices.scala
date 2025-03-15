package io.greensheep.trading.service.services

import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.dataservice.{MongoDbDataRepo, StockUniverse}
import zio.*
import zio.stream.ZStream

import scala.jdk.CollectionConverters.*
import java.time.LocalDate
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.language.postfixOps
import scala.util.Try

object QuotesServices {

  implicit val WriteExecutionContext: ExecutionContextExecutor = ExecutionContext.global

  private def datesBetween(startDate: LocalDate, endDate: LocalDate) = startDate.datesUntil(endDate).iterator().asScala.toSeq

  def getDailySnapshotService(start: LocalDate, end: LocalDate): ZIO[MongoDbDataRepo with PolygonAsyncApi, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      repo <- ZIO.service[MongoDbDataRepo]
      api <- ZIO.service[PolygonAsyncApi]
      datesToRetrieve = datesBetween(start, end)
      _ <- ZIO.cond(datesToRetrieve.nonEmpty, (), new IllegalArgumentException("No dates to retrieve"))
      _ <- ZIO.logInfo(s"Retrieving daily snapshots from ${datesToRetrieve.head} to ${datesToRetrieve.last}")
      dateStream = ZStream.fromIterable(datesToRetrieve)
      aggregateMaps = dateStream.mapZIO(api.getGroupedDaily).map(_.map(x => x.T.getOrElse("?") -> x).toMap)
      r = aggregateMaps.grouped(10).map(_.flatMap(_.toSeq).groupBy(_._1).transform((_,v) => v.map(_._2))) mapZIO {
        date =>
          val results = date.map {
            case (ticker, aggregates) =>
              for{
                _ <- ZIO.fromTry(Try{
                  repo.updateAggregates(ticker, aggregates, overwrite = false)
                  ticker
                })
              } yield ()

          }
          ZIO.collectAll(results) flatMap  {
            it =>
              ZIO.logInfo(s"Saved to MongoDB ${it.size} records")
          }
      }
      _ <- r.runCollect
      _ <- repo.saveStockUniverse(StockUniverse("us-stocks", "US common stocks", end, Seq.empty))
      _ <- ZIO.logInfo(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }



  def updateMissingQuotes(): ZIO[MongoDbDataRepo with PolygonAsyncApi, Throwable, Unit] = {
    for {
      repo <- ZIO.service[MongoDbDataRepo]
      univ <- repo.getStockUniverse("us-stocks")
      startDate = univ.lastUpdateQuotes.minusDays(1)
      _ <- ZIO.logInfo(s"Last update was on ${univ.lastUpdateQuotes}")
      endDate = LocalDate.now().minusDays(1)
      _ <- getDailySnapshotService(startDate, endDate)
    } yield ()
  }


}
