package io.greensheep.trading.service

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.MongoDataRepo
import io.greensheep.trading.service.app.{AppHealth, CronJobs, WebApp}
import io.greensheep.trading.service.services.{ApiEndpoints, FiancialsServices, InitializationService}
import zio.*
import zio.http.{Client, DnsResolver, QueryParams, Server, URL, ZClient}
import zio.http.netty.NettyConfig
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault {
  java.lang.System.setProperty("java.net.preferIPv4Stack", "true")
  java.lang.System.setProperty("java.net.preferIPv6Addresses", "false")
  java.nio.file.Files.createDirectories(java.nio.file.Paths.get("logs"))

  private val logger: ZLayer[Any, Nothing, Unit] = SLF4J.slf4j

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers ++ logger

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] = {
    val polygonApiKey = LocalConfig.require("polygon.api.key")
    val finnhubApiKey = LocalConfig.require("finnhub.api.key")
    val skipInitialization = LocalConfig.get("ttk.skip.initialization").exists(_.equalsIgnoreCase("true"))
    val skipCron = LocalConfig.get("ttk.skip.cron").exists(_.equalsIgnoreCase("true"))
    val mongoUrl = LocalConfig.get("mongodb.url").getOrElse("mongodb://127.0.0.1:27017")

    // Keep-alive sockets sit idle between Finnhub tickers (~1.1s) and overnight cron gaps.
    // zio-http's default idle timeout fires ReadTimeoutException on the Netty event loop.
    val outboundHttpClient =
      (ZLayer.succeed(ZClient.Config.default.noIdleTimeout) ++
        ZLayer.succeed(NettyConfig.default) ++
        DnsResolver.default) >>> Client.live

    val polygonClientLayer = outboundHttpClient.map(_.update { client =>
      client.url(
        URL
          .decode("https://api.polygon.io")
          .toOption
          .get
          .addQueryParams(QueryParams("apiKey" -> polygonApiKey))
      )
    })

    val polygonLayer = ZLayer.fromZIO(PolygonAsyncApi.build())
    val environment = polygonClientLayer >>> polygonLayer
    val finnhubClientLayer = outboundHttpClient
    val finnhubLayer = ZLayer.succeed(FinnhubClient.make(finnhubApiKey))
    val mongoClient = MongoClient.live(mongoUrl)
    val polygonMongoLayer = mongoClient >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("polygon")))

    val restPort = 7777
    val restServer = Server.defaultWithPort(restPort)

    val everything = for {
      _ <- ZIO.logInfo("Starting rest api")
      _ <- MongoDataRepo.ensureIndexes.catchAll(error => ZIO.logWarning(s"Index creation skipped: ${error.getMessage}"))
      allApi = (ApiEndpoints.financialsRoutes(polygonMongoLayer) ++ ApiEndpoints.OtherRoutes).toHttpApp
      webSource = WebApp.describeSource
      _ <- ZIO.logInfo(s"Serving web app from $webSource")
      _ <- ZIO
        .logWarning("Web app not found on disk or classpath. Run `sbt service/packageService` or `npm run build` in web/.")
        .when(webSource == "missing")
      _ <- Server.install(allApi ++ WebApp.app)
      _ <- ZIO.logInfo(s"Rest api started on port $restPort")
      _ <- CronJobs.start.unless(skipCron)
      _ <- ZIO.logInfo("Cron jobs started").unless(skipCron)
      _ <- ZIO.logInfo("Cron jobs skipped").when(skipCron)
      _ <- (
        InitializationService.initializeApp() *>
          FiancialsServices.updateFinancials()
      ).catchAll { error =>
        ZIO.logError(s"Background initialization failed: ${error.getMessage}")
      }.forkDaemon.unless(skipInitialization)
      _ <- ZIO.logInfo("Bulk initialization skipped").when(skipInitialization)
      _ <- ZIO.never
    } yield ()

    everything.provideSome[Scope](
      environment ++ polygonMongoLayer ++ restServer ++ AppHealth.layer ++ finnhubLayer ++ finnhubClientLayer
    )
  }
}
