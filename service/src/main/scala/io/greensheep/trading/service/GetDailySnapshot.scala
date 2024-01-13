package io.greensheep.trading.service

import io.greensheep.providers.polygon.datatypes.Aggregate
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.ZIOAppDefault
import zio.*
import zio.interop.catz.*
import zio.http.Client
import zio.stream.{ZStream, Stream}
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object GetDailySnapshot extends ZIOAppDefault {

  private def getDatesBetween(startDate: LocalDate, endDate: LocalDate): Stream[Throwable, LocalDate] = {
      ZStream.fromJavaStream(startDate.datesUntil(endDate))
  }

  val datesToRetrieve = getDatesBetween(LocalDate.now().minusDays(100), LocalDate.now())

  def flattenChunk(chunk: Chunk[ZIO[Any, Throwable, Map[String, Aggregate]]]) = {
    //TODO
  }

  val getAggregatesRangeApp: ZIO[MongoDbDataRepo with PolygonDataSource, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      responseAggregates = datesToRetrieve.schedule(Schedule.spaced(16.seconds))
        .map(_.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
        .map(source.getSnapshot).grouped(3)
        .map(c => ZIO.collectAll(c).map(Chunk.fromIterable)) map {
        zioChunk =>
          zioChunk map {
            chunk =>
              chunk.flatMap(_.toList).groupBy(_._1).view.mapValues(_.map(_._2)).toMap
          }
      }
      _ <- responseAggregates.foreach(_.map(allStocks => {
        val time = java.lang.System.currentTimeMillis()
        val r = allStocks.map(kv => repo.saveAggregates(kv._1, kv._2))
        println(s"Saved ${allStocks.size} stocks in ${(java.lang.System.currentTimeMillis() - time)/1000} sec")
        r
      }))
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  val getAggregatesApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]

      allData <- source.getSnapshot("2024-01-09")
      _ <- ZIO.foreachParDiscard(allData) {
        case (ticker, agg) =>
          ZIO.succeed(repo.saveAggregates(ticker, Seq(agg)))
      }
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    getAggregatesRangeApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer ++ Client.default)
  }
}
