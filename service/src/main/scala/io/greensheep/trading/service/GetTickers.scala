package io.greensheep.trading.service

import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.GetAggregates.Environment
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault}
import zio.json._
object GetTickers extends ZIOAppDefault{

  val getTickersApp: ZIO[MongoDbDataRepo with PolygonDataSource, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      tickerStream = source.getTickers
      _ <- tickerStream.foreach(t => ZIO.succeed(println(t)))
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  val getSingleTickerApp: ZIO[MongoDbDataRepo with PolygonDataSource, Throwable, Unit] = {
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      stock <- source.getStock("AAPL")
      _ = println(stock.toJsonPretty)

    } yield ()
  }


  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    getSingleTickerApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer)

  }
}
