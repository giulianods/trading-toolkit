package io.greensheep.trading.service

import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.api.ApiEndpoints
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.Quote
import io.greensheep.trading.dataservice.{LocalFileDataRepo, MongoDataRepo, MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.app.{CronJobs, WebApp}
import io.greensheep.trading.service.services.{InitializationService, QuotesServices}
import zio.*
import zio.http.{Client, Server, URL}
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


  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] = {

    val apiKey = java.lang.System.getenv("POLYGON_API_KEY")

    val clientLayer = Client.default.map(_.update(
      client =>
        client.addHeader(Authorization.Bearer(apiKey))
          .url(URL.decode("https://api.polygon.io").toOption.get)
    ))

    val polygonLayer = ZLayer.fromZIO(PolygonAsyncApi.build())

    val environment = clientLayer >>> polygonLayer

    val logging = Runtime.removeDefaultLoggers ++ logger

    val mongoLayer = MongoClient.live("mongodb://127.0.0.1:27017") >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("polygon")))
    // MongoDataRepo.build("mongodb://127.0.0.1:27017", "polygon")

    val intitializer = InitializationService.initializeApp()

    val everything = for {
      _ <- ZIO.logInfo("Starting initialization")
      _ <- intitializer
      _ <- ZIO.logInfo("Initialization completed")
      _ <- ZIO.logInfo("Starting rest api")
      api = ApiEndpoints.buildRestApi
      web = WebApp.app
      _ <- Server.install(api ++ web)
      _ <- ZIO.logInfo(s"Rest api started on ${Server.Config.default.address}")
      _ <- CronJobs.start
      _ <- ZIO.logInfo("Cron jobs started")
      _ <- ZIO.never
    } yield ()

    val program = everything.provide(environment ++ mongoLayer ++ logging ++ Server.defaultWithPort(7777))

    program catchAll {
      th =>
        th.printStackTrace()
        ZIO.fail(th)
    }

  }
}