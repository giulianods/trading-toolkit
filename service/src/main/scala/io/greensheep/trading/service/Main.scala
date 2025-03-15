package io.greensheep.trading.service

import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.Quote
import io.greensheep.trading.dataservice.{LocalFileDataRepo, MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.services.QuotesServices
import zio.*
import zio.http.{Client, URL}
import zio.http.Header.Authorization
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

import scala.jdk.CollectionConverters.*
import java.time.LocalDate

object Main extends ZIOAppDefault {

  private def datesBetween(startDate: LocalDate, endDate: LocalDate) = startDate.datesUntil(endDate).iterator().asScala.toSeq

  val toRetrieve = datesBetween(LocalDate.of(2024 ,4 ,1), LocalDate.now().minusDays(1))



  // Configure the logger to use the custom format
  private val logger: ZLayer[Any, Nothing, Unit] = SLF4J.slf4j


  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {

    val apiKey = java.lang.System.getenv("POLYGON_API_KEY")

    val clientLayer = Client.default.map(_.update(
      client =>
        client.addHeader(Authorization.Bearer(apiKey))
          .url(URL.decode("https://api.polygon.io").toOption.get)
    ))

    val polygonLayer = ZLayer.fromZIO(PolygonAsyncApi.build())

    val environment = clientLayer >>> polygonLayer

    val logging = Runtime.removeDefaultLoggers ++ logger

    val program = QuotesServices.getDailySnapshotService(LocalDate.of(2025 ,1 ,1), LocalDate.now().minusDays(1)).provide(environment ++ MongoDbDataRepo.layer ++ logging)

    program catchAll {
      th =>
        th.printStackTrace()
        ZIO.fail(th)
    }

  }
}