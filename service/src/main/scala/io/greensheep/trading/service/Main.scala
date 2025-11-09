package io.greensheep.trading.service

import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.api.ChartServices
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.Quote
import io.greensheep.trading.dataservice.{LocalFileDataRepo, MongoDataRepo, MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.app.{CronJobs, WebApp}
import io.greensheep.trading.service.services.{ApiEndpoints, InitializationService, QuotesServices}
import zio.*
import zio.http.{Client, Server, URL}
import zio.http.Header.Authorization
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

import scala.jdk.CollectionConverters.*
import java.time.LocalDate
import io.finnhub.client.FinnhubClient
import io.greensheep.trading.service.services.FiancialsServices

object Main extends ZIOAppDefault {

  private def datesBetween(startDate: LocalDate, endDate: LocalDate) = startDate.datesUntil(endDate).iterator().asScala.toSeq

  val toRetrieve = datesBetween(LocalDate.of(2024 ,4 ,1), LocalDate.now().minusDays(1))

  // Configure the logger to use the custom format
  private val logger: ZLayer[Any, Nothing, Unit] = SLF4J.slf4j

  // Override the default runtime to ensure logging configuration is applied globally
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers ++ logger


  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] = {

    val polygonApiKey = java.lang.System.getenv("POLYGON_API_KEY")
    val finnhubApiKey = java.lang.System.getenv("FINNHUB_API_KEY")

    val polygonClientLayer = Client.default.map(_.update(
      client =>
        client.addHeader(Authorization.Bearer(polygonApiKey))
          .url(URL.decode("https://api.polygon.io").toOption.get)
    ))

    val finnhubClientLayer = Client.default.map(_.update(
      client =>
        client.addHeader("X-Finnhub-Token", finnhubApiKey)
    ))

    val polygonLayer = ZLayer.fromZIO(PolygonAsyncApi.build())
    val environment = polygonClientLayer >>> polygonLayer

    val finnhubLayer = ZLayer.succeed(new FinnhubClient(finnhubClientLayer))

    val mongoClient = MongoClient.live("mongodb://127.0.0.1:27017")

    val polygonMongoLayer = mongoClient >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("polygon")))
    val finnhubMongoLayer = mongoClient >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("finnhub")))

    
    val initFinancials = FiancialsServices.updateFinancials().provide(finnhubLayer ++ finnhubMongoLayer)

    val chartServiceLayer = polygonMongoLayer >>> ChartServices.buildLayer

    val restPort = 7777

    val restServer = Server.defaultWithPort(restPort)


    val intitializer = InitializationService.initializeApp()

    val allRoutes =  ApiEndpoints.OtherRoutes
    val finRoutes = ApiEndpoints.financialsRoutes(finnhubMongoLayer)
   

    val everything = for {
      _ <- ZIO.logInfo("Starting initialization")
      _ <- intitializer
      //_ <- ZIO.logInfo("Initialization completed")
      _ <- initFinancials
      _ <- ZIO.logInfo("Starting rest api")
      allApi = (finRoutes ++ allRoutes).toHttpApp
      web = WebApp.app
      _ <- Server.install(allApi ++ web)
      _ <- ZIO.logInfo(s"Rest api started on port $restPort")
      _ <- CronJobs.start
      _ <- ZIO.logInfo("Cron jobs started")
      _ <- ZIO.never
    } yield ()

    val program = everything.provide(environment ++ polygonMongoLayer ++ restServer ++ chartServiceLayer)

    program catchAll {
      th =>
        th.printStackTrace()
        ZIO.fail(th)
    }

  }
}