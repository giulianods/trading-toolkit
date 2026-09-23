package io.greensheep.trading.service

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.providers.jquants.JquantsClient
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.providers.xetra.XetraClient
import io.greensheep.trading.core.types.{MarketDefinition, Markets}
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.{AppHealth, CronJobs, WebApp}
import io.greensheep.trading.service.services.{ApiEndpoints, BvbInitialization, FiancialsServices, InitializationService, JquantsInitialization, QuotesServices, XetraInitialization}
import zio.*
import zio.http.{Client, DnsResolver, Server, ZClient}
import zio.http.netty.NettyConfig
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault {
  java.lang.System.setProperty("java.net.preferIPv4Stack", "true")
  java.lang.System.setProperty("java.net.preferIPv6Addresses", "false")
  java.nio.file.Files.createDirectories(java.nio.file.Paths.get("logs"))

  private val logger: ZLayer[Any, Nothing, Unit] = SLF4J.slf4j

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers ++ logger

  override def run: ZIO[Environment & ZIOAppArgs & Scope, Any, Any] = {
    val enabled = Markets.enabledFromConfig.fold(msg => throw new IllegalStateException(msg), identity)
    val needsMassive = enabled.exists(_.id.provider == "massive")
    val needsJquants = enabled.exists(_.id.provider == "jquants")
    val needsFinnhub = enabled.exists(_.hasFinancials)
    if (needsMassive) LocalConfig.require("massive.api.key")
    if (needsJquants) LocalConfig.require("jquants.api.key")
    val finnhubApiKey = if (needsFinnhub) LocalConfig.require("finnhub.api.key") else ""
    val skipInitialization = LocalConfig.get("ttk.skip.initialization").exists(_.equalsIgnoreCase("true"))
    val skipCron = LocalConfig.get("ttk.skip.cron").exists(_.equalsIgnoreCase("true"))
    val mongoUrl = LocalConfig.get("mongodb.url").getOrElse("mongodb://127.0.0.1:27017")

    val outboundHttpClient =
      (ZLayer.succeed(ZClient.Config.default.noIdleTimeout) ++
        ZLayer.succeed(NettyConfig.default) ++
        DnsResolver.default) >>> Client.live

    val massiveLayer = MassiveAsyncApi.buildLayer
    val finnhubLayer = ZLayer.succeed(FinnhubClient.make(if (finnhubApiKey.isEmpty) "unused" else finnhubApiKey))
    val mongoClient = MongoClient.live(mongoUrl)
    val restServer = Server.defaultWithPort(7777)

    val everything = for {
      client <- ZIO.service[MongoClient]
      store <- ZIO.fromEither(MarketStore.live(client)).mapError(msg => new IllegalStateException(msg))
      _ <- ZIO.logInfo(s"Enabled markets: ${store.enabled.map(_.id.id).mkString(", ")}")
      _ <- ZIO.foreachParDiscard(store.enabled) { market =>
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
      _ <- ZIO.logInfo("Rest api started on port 7777")
      _ <- ZIO.logInfo("Cron jobs skipped").when(skipCron)
      _ <- ZIO.logInfo("Bulk initialization skipped").when(skipInitialization)
      _ <- ZIO.never
    } yield ()

    everything.provideSome[Scope](
      outboundHttpClient,
      massiveLayer,
      mongoClient,
      restServer,
      AppHealth.layer,
      finnhubLayer,
      BvbClient.live,
      XetraClient.live,
      JquantsClient.live
    )
  }

  private def startMarket(
      client: MongoClient,
      store: MarketStore,
      market: MarketDefinition,
      skipInitialization: Boolean,
      skipCron: Boolean
  ): ZIO[MassiveAsyncApi & BvbClient & XetraClient & JquantsClient & AppHealth & FinnhubClient & Client & Scope, Throwable, Unit] = {
    val dbLayer = ZLayer.succeed(store.database(market))
    market.id.provider match {
      case "massive" =>
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
          // When full init is skipped, still run quote hygiene (ticker reuse / unadjusted jumps).
          _ <- QuotesServices.nightlyHygiene
            .provideSomeLayer(dbLayer)
            .catchAll { error =>
              ZIO.logError(s"Startup quote hygiene for ${market.id.id} failed: ${error.getMessage}")
            }
            .forkDaemon
            .when(skipInitialization)
          _ <- CronJobs.start(market).provideSomeLayer(dbLayer).unless(skipCron)
          _ <- ZIO.logInfo(s"Cron jobs started for ${market.id.id}").unless(skipCron)
        } yield ()
      case "bvb" =>
        for {
          _ <- ZIO.logInfo(s"Opening market ${market.id.id} database ${market.id.databaseName}")
          _ <- MongoDataRepo.ensureIndexes
            .provideLayer(dbLayer)
            .catchAll(error => ZIO.logWarning(s"Index creation skipped for ${market.id.id}: ${error.getMessage}"))
          _ <- BvbInitialization.initializeApp(market)
            .provideSomeLayer(dbLayer)
            .catchAll { error =>
              ZIO.logError(s"Background initialization for ${market.id.id} failed: ${error.getMessage}")
            }
            .forkDaemon
            .unless(skipInitialization)
          _ <- CronJobs.startBvb(market).provideSomeLayer(dbLayer).unless(skipCron)
          _ <- ZIO.logInfo(s"Cron jobs started for ${market.id.id}").unless(skipCron)
        } yield ()
      case "xetra" =>
        for {
          _ <- ZIO.logInfo(s"Opening market ${market.id.id} database ${market.id.databaseName}")
          _ <- MongoDataRepo.ensureIndexes
            .provideLayer(dbLayer)
            .catchAll(error => ZIO.logWarning(s"Index creation skipped for ${market.id.id}: ${error.getMessage}"))
          _ <- XetraInitialization.initializeApp(market)
            .provideSomeLayer(dbLayer)
            .catchAll { error =>
              ZIO.logError(s"Background initialization for ${market.id.id} failed: ${error.getMessage}")
            }
            .forkDaemon
            .unless(skipInitialization)
          _ <- CronJobs.startXetra(market).provideSomeLayer(dbLayer).unless(skipCron)
          _ <- ZIO.logInfo(s"Cron jobs started for ${market.id.id}").unless(skipCron)
        } yield ()
      case "jquants" =>
        for {
          _ <- ZIO.logInfo(s"Opening market ${market.id.id} database ${market.id.databaseName}")
          _ <- MongoDataRepo.ensureIndexes
            .provideLayer(dbLayer)
            .catchAll(error => ZIO.logWarning(s"Index creation skipped for ${market.id.id}: ${error.getMessage}"))
          _ <- JquantsInitialization.initializeApp(market)
            .provideSomeLayer(dbLayer)
            .catchAll { error =>
              ZIO.logError(s"Background initialization for ${market.id.id} failed: ${error.getMessage}")
            }
            .forkDaemon
            .unless(skipInitialization)
          _ <- CronJobs.startJquants(market).provideSomeLayer(dbLayer).unless(skipCron)
          _ <- ZIO.logInfo(s"Cron jobs started for ${market.id.id}").unless(skipCron)
        } yield ()
      case other =>
        ZIO.fail(new IllegalStateException(s"No quote client for provider '$other'"))
    }
  }
}
