package io.greensheep.trading.service

import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.ZIOAppDefault
import zio.*
import zio.interop.catz.*
import zio.http.Client

object GetDailySnapshot extends ZIOAppDefault {


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
