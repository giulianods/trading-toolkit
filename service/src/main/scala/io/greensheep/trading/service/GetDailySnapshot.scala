package io.greensheep.trading.service

import io.greensheep.providers.polygon.datatypes.Aggregate
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.ZIOAppDefault
import zio.*
import zio.interop.catz.*
import zio.http.Client
import zio.stream.ZStream

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object GetDailySnapshot extends ZIOAppDefault {

  private def getDatesBetween(startDate: LocalDate, endDate: LocalDate): ZStream[Any, Throwable, LocalDate] = {
      ZStream.fromJavaStream(startDate.datesUntil(endDate))
  }

  val datesToRetrieve = getDatesBetween(LocalDate.now().minusDays(3 * 365), LocalDate.now())

  def flattenChunk(chunk: Chunk[ZIO[Any, Throwable, Map[String, Aggregate]]]) = {
    //TODO

  }

  val getAggregatesRangeApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      responseChunks = datesToRetrieve.grouped(10) map {
        dates => dates.map(_.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
          .map(source.getSnapshot)
      }


      allData <- source.getSnapshot("2024-01-09")
      _ = allData foreach {
        case (ticker, agg) => repo.saveAggregates(ticker, Seq(agg))
      }
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  val getAggregatesApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]

      allData <- source.getSnapshot("2024-01-09")
      _ = allData foreach {
        case(ticker, agg) => repo.saveAggregates(ticker, Seq(agg))
      }
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    getAggregatesApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer ++ Client.default)
  }
}
