package io.greensheep.trading.service

import io.greensheep.providers.polygon.requests.TickersRequest
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.{Schedule, Scope, ZIO, ZIOAppArgs, ZIOAppDefault}
import zio.durationInt

import java.time.LocalDateTime

object TestPolygonApi extends ZIOAppDefault{

  val getTickersApp: ZIO[MongoDbDataRepo with PolygonDataSource, Throwable, Unit] = {
    val request = TickersRequest(_type = "CS",
      market = Some("stocks"), limit = 1000)

    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      tickerStream = source.getTickers
      _ <- tickerStream.schedule(Schedule.spaced(16.seconds)) foreach {
        ticker =>
          for {
            s <- source.getStock(ticker.symbol)
            _ = println(s"Got data for ${ticker.symbol} at ${LocalDateTime.now()}")
            r = repo.saveTicker(s)
          } yield ()
      }
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    getTickersApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer)
  }
}
