package io.greensheep.trading.service

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.trading.core.types.{MarketDefinition, Markets}
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
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
    val massiveApiKey = LocalConfig.require("massive.api.key")
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

    val massiveClientLayer = outboundHttpClient.map(_.update { client =>
      client.url(
        URL
          .decode("https://api.massive.com")
          .toOption
          .get
          .addQueryParams(QueryParams("apiKey" -> massiveApiKey))
      )
    })

    val massiveLayer = ZLayer.fromZIO(MassiveAsyncApi.build())
    val environment = massiveClientLayer >>> massiveLayer
    val finnhubClientLayer = outboundHttpClient
    val finnhubLayer = ZLayer.succeed(FinnhubClient.make(finnhubApiKey))
    val mongoClient = MongoClient.live(mongoUrl)

    val restPort = 7777
    val restServer = Server.defaultWithPort(restPort)

    val everything = for {
      client <- ZIO.service[MongoClient]
      store <- ZIO.fromEither(MarketStore.live(client)).mapError(msg => new IllegalStateException(msg))
      _ <- ZIO.logInfo(s"Enabled markets: ${store.enabled.map(_.id.id).mkString(", ")}")
      _ <- ZIO.foreachDiscard(store.enabled) { market =>
        startMarket(client, store, market, skipInitialization, skipCron)
      }
      allApi = ApiEndpoints.routes(store).toHttpApp
      webSource = WebApp.describeSource
      _ <- ZIO.logInfo("Starting rest api")
      _ <- ZIO.logInfo(s"Serving web app from $webSource")
      _ <- ZIO
        .logWarning("Web app not found on disk or classpath. Run `sbt service/packageService` or `npm run build` in web/.")
        .when(webSource == "missing")
      _ <- Server.install(allApi ++ WebApp.app)
      _ <- ZIO.logInfo(s"Rest api started on port $restPort")
      _ <- ZIO.logInfo("Cron jobs skipped").when(skipCron)
      _ <- ZIO.logInfo("Bulk initialization skipped").when(skipInitialization)
      _ <- ZIO.never
    } yield ()

    everything.provideSome[Scope](
      environment ++ mongoClient ++ restServer ++ AppHealth.layer ++ finnhubLayer ++ finnhubClientLayer
    )
  }

  private def startMarket(
      client: MongoClient,
      store: MarketStore,
      market: MarketDefinition,
      skipInitialization: Boolean,
      skipCron: Boolean
  ): ZIO[MassiveAsyncApi & AppHealth & FinnhubClient & Client & Scope, Throwable, Unit] = {
    market.id.provider match {
      case "massive" =>
        val dbLayer = ZLayer.succeed(store.database(market))
        for {
          _ <- ZIO.logInfo(s"Opening market ${market.id.id} database ${market.id.databaseName}")
          _ <- MongoDataRepo.migrateLegacyUsIfNeeded(client).when(market.id == Markets.UsMassive.id)
          _ <- MongoDataRepo.ensureIndexes
            .provideLayer(dbLayer)
            .catchAll(error => ZIO.logWarning(s"Index creation skipped for ${market.id.id}: ${error.getMessage}"))
          _ <- (
            InitializationService.initializeApp(market) *>
              (if (market.hasFinancials) FiancialsServices.updateFinancials() else ZIO.unit)
          ).provideSomeLayer(dbLayer)
            .catchAll { error =>
              ZIO.logError(s"Background initialization for ${market.id.id} failed: ${error.getMessage}")
            }
            .forkDaemon
            .unless(skipInitialization)
          _ <- CronJobs.start(market).provideSomeLayer(dbLayer).unless(skipCron)
          _ <- ZIO.logInfo(s"Cron jobs started for ${market.id.id}").unless(skipCron)
        } yield ()
      case other =>
        ZIO.fail(new IllegalStateException(s"No quote client for provider '$other'"))
    }
  }
}
