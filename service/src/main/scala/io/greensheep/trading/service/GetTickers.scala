package io.greensheep.trading.service

import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.GetAggregates.Environment
import zio.json.*
import zio.*

import java.time.LocalDateTime

object GetTickers extends ZIOAppDefault{

  val getTickersApp: ZIO[MongoDbDataRepo with PolygonDataSource, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      tickerStream = source.getTickers
      _ <- tickerStream.schedule(Schedule.spaced(16.seconds)) foreach {
        ticker => for{
          s <- source.getStock(ticker.symbol)
          _ = println(s"Got data for ${ticker.symbol} at ${LocalDateTime.now()}")
          r = repo.saveTicker(s)
        } yield()
      }
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  val getSingleTickerApp: ZIO[MongoDbDataRepo with PolygonDataSource, Throwable, Unit] = {
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      stock <- source.getStock("AAPL")
      _ = println(stock.toJsonPretty)
      _ = repo.saveTicker(stock)

    } yield ()
  }


  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    getTickersApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer)

  }
}
