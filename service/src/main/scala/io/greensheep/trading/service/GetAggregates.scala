package io.greensheep.trading.service

import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.ZIOAppDefault
import zio.*
import zio.interop.catz.*
import zio.http.Client

object GetAggregates extends ZIOAppDefault {


  val getAggregatesApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      tickerStream = repo.getTickers
      xx <- tickerStream.schedule(Schedule.spaced(16.seconds)) foreach {
        ticker =>
          for {
            aggs <- source.getDailyAggregates(ticker.symbol)
            _ = repo.saveAggregates(ticker.symbol, aggs)
          } yield ()
      }
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() -  time) / 1000} seconds")
    } yield ()
  }

  val getAggregatesFromMongo: ZIO[MongoDbDataRepo with PolygonDataSource, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      tickerStream = repo.getAggregates("AAPL")
      _ <- tickerStream.foreach(t => ZIO.succeed(println(t)))
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    getAggregatesApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer ++ Client.default)

  }
}
