package io.greensheep.trading.service

import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.GetDailySnapshot.getAggregatesRangeApp
import zio.{Scope, ZIO, ZIOAppArgs, ZIOAppDefault}
import zio.http.Header.Authorization
import zio.http.{Client, URL}

import scala.util.Try

object GetTickerDetails extends ZIOAppDefault{


  val app = {
    for {
      repo <- ZIO.service[MongoDbDataRepo]
      api <- PolygonAsyncApi.build()
      stock <- api.getTickers.foreach( ticker =>
        for{
          details <- api.getTickerDetails(ticker.ticker)
          _ = details.map(repo.saveTicker)(scala.concurrent.ExecutionContext.global)
        } yield()
      )


    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    val apiKey = java.lang.System.getenv("POLYGON_API_KEY")

    val clientLayer = Client.default.map(_.update(
      client =>
        client.addHeader(Authorization.Bearer(apiKey))
          .url(URL.decode("https://api.polygon.io").toOption.get)
    ))

    app.provide(MongoDbDataRepo.layer ++ clientLayer)  }
}
