package io.greensheep.trading.service

import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.http.{Client, URL}
import zio.http.Header.Authorization
import zio.stream.ZStream
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault}

import java.time.LocalDate

object UpdateSplitted extends ZIOAppDefault{

  val getAggregatesApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      api <- PolygonAsyncApi.build()
      repo <- ZIO.service[MongoDbDataRepo]
      _ = println("Data sources initialized")
      splits <- api.getSplits(LocalDate.now().minusDays(100), LocalDate.now()).mapError(th => {
        th.printStackTrace()
        th
      })
      _ = println(s"Got ${splits.size} splits")
      _ <- ZStream.fromIterable(splits.map(_.ticker)) foreach {
        ticker =>
          println("Getting aggregates for " + ticker)
          for {
            aggs <- api.getAggregates(ticker, LocalDate.now().minusDays(400))
            _ = repo.updateAggregates(ticker, aggs, overwrite = true)
          } yield ()
      }

      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }


  override def run: ZIO[Any with ZIOAppArgs with Scope, Any, Any] = {
    val apiKey = java.lang.System.getenv("POLYGON_API_KEY")
    val clientLayer = Client.default.map(_.update(
      client =>
        client.addHeader(Authorization.Bearer(apiKey))
          .url(URL.decode("https://api.polygon.io").toOption.get)
    ))
    getAggregatesApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer ++ clientLayer)

  }
}
