package io.greensheep.trading.service

import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.finnhub.client.FinnhubClient
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.api.ChartServices
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.Quote
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.{LocalFileDataRepo, MongoDataRepo, MongoDbDataRepo, PolygonDataSource}
import io.greensheep.trading.service.app.{CronJobs, WebApp}
import io.greensheep.trading.service.services.{ApiEndpoints, InitializationService, QuotesServices}
import zio.*
import zio.http.{Client, QueryParams, Server, URL}
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

import scala.jdk.CollectionConverters.*
import java.time.LocalDate
import io.greensheep.trading.service.services.FiancialsServices

object Main extends ZIOAppDefault {
  // Some environments resolve finnhub.io to IPv6 without a working IPv6 route.
  // Prefer IPv4 sockets to avoid NoRouteToHostException on IPv6 destinations.
  java.lang.System.setProperty("java.net.preferIPv4Stack", "true")
  java.lang.System.setProperty("java.net.preferIPv6Addresses", "false")

  private def datesBetween(startDate: LocalDate, endDate: LocalDate) = startDate.datesUntil(endDate).iterator().asScala.toSeq

  val toRetrieve = datesBetween(LocalDate.of(2024 ,4 ,1), LocalDate.now().minusDays(1))

  // Configure the logger to use the custom format
  private val logger: ZLayer[Any, Nothing, Unit] = SLF4J.slf4j

  // Override the default runtime to ensure logging configuration is applied globally
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers ++ logger


  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] = {

    val polygonApiKey = LocalConfig.require("polygon.api.key")
    val finnhubApiKey = LocalConfig.require("finnhub.api.key")
    val skipInitialization = LocalConfig.get("ttk.skip.initialization").exists(_.equalsIgnoreCase("true"))

    val polygonClientLayer = Client.default.map(_.update(
      client =>
        client.url(
          URL.decode("https://api.polygon.io").toOption.get
            .addQueryParams(QueryParams("apiKey" -> polygonApiKey))
        )
    ))

    val polygonLayer = ZLayer.fromZIO(PolygonAsyncApi.build())
    val environment = polygonClientLayer >>> polygonLayer

    val finnhubClientLayer = Client.default
    val finnhubLayer = ZLayer.succeed(FinnhubClient.make(finnhubApiKey))

    val mongoClient = MongoClient.live("mongodb://127.0.0.1:27017")

    val polygonMongoLayer = mongoClient >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("polygon")))
    val finnhubMongoLayer = mongoClient >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("finnhub")))

    
    val initFinancials = FiancialsServices.updateFinancials().provideSome[Scope](finnhubLayer ++ finnhubMongoLayer ++ finnhubClientLayer)

    val chartServiceLayer = polygonMongoLayer >>> ChartServices.buildLayer
    val financialChartServiceLayer = finnhubMongoLayer >>> ChartServices.buildLayer

    val restPort = 7777

    val restServer = Server.defaultWithPort(restPort)


    val intitializer = InitializationService.initializeApp()

    val allRoutes =  ApiEndpoints.OtherRoutes
    val finRoutes = ApiEndpoints.financialsRoutes(finnhubMongoLayer, financialChartServiceLayer)
   

    val everything = for {
      _ <- ZIO.logInfo("Starting initialization")
      _ <- intitializer.unless(skipInitialization)
      _ <- initFinancials.unless(skipInitialization)
      _ <- ZIO.logInfo("Bulk initialization skipped").when(skipInitialization)
      _ <- ZIO.logInfo("Starting rest api")
      allApi = (finRoutes ++ allRoutes).toHttpApp
      web = WebApp.app
      _ <- Server.install(allApi ++ web)
      _ <- ZIO.logInfo(s"Rest api started on port $restPort")
      _ <- CronJobs.start.unless(skipInitialization)
      _ <- ZIO.logInfo("Cron jobs started").unless(skipInitialization)
      _ <- ZIO.never
    } yield ()

    val program = everything.provideSome[Scope](environment ++ polygonMongoLayer ++ restServer ++ chartServiceLayer)

    program catchAll {
      th =>
        th.printStackTrace()
        ZIO.fail(th)
    }

  }
}
