package io.greensheep.trading.service

import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.dataservice.{LocalFileDataRepo, MongoDbDataRepo, PolygonDataSource}
import zio.http.Client
import zio.{ZIO, *}


object GetFinancials extends ZIOAppDefault {


  val getFinancialsApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]

      allData = source.getFinancials("AAPL")
      _ <- allData.foreach(a => ZIO.succeed(println(a)))
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    getFinancialsApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer ++ Client.default)
  }
}
