package io.greensheep.trading.service

import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.GetAndWrite.Environment
import zio.*
import zio.interop.catz.*
import zio.http.Client
object GetAndWrite extends ZIOAppDefault {

  val downloadApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {

    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      tickerStream = source.getTickers
      _ <- tickerStream.foreach(t => ZIO.succeed(repo.saveTicker(t)))

    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    //val layers: ZLayer[Any, Nothing, MongoDbDataRepo with PolygonDataSource] = MongoDbDataRepo.layer ++ PolygonDataSource.layer
    downloadApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer ++ Client.default)

  }

}
