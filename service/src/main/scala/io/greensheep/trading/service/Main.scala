package io.greensheep.trading.service

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.providers.eodhd.EodhdClient
import io.greensheep.providers.jquants.JquantsClient
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.providers.xetra.XetraClient
import io.greensheep.trading.core.types.{MarketDefinition, Markets}
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.{AppHealth, CronJobs, WebApp}
import io.greensheep.trading.service.services.{AlpacaQuotesServices, ApiEndpoints, BvbInitialization, EodhdQuotesServices, FiancialsServices, InitializationService, JquantsInitialization, McpEndpoints, QuotesServices, ScreenerServices, XetraInitialization}
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
    val screens = ScreenerServices.screenOptions
    val skipInitialization = LocalConfig.get("ttk.skip.initialization").exists(_.equalsIgnoreCase("true"))
    val skipCron = LocalConfig.get("ttk.skip.cron").exists(_.equalsIgnoreCase("true"))
    val needsMassive = enabled.exists(_.id.provider == "massive")
    val needsJquants = enabled.exists(_.id.provider == "jquants")
    if (needsMassive || enabled.contains(Markets.UsAlpaca)) LocalConfig.require("massive.api.key")
    if (needsJquants) LocalConfig.require("jquants.api.key")
    if (enabled.contains(Markets.CaEodhd) && (!skipInitialization || !skipCron)) LocalConfig.require("eodhd.api.key")
    if (enabled.contains(Markets.UsAlpaca)) {
      LocalConfig.require("alpaca.api.key")
      LocalConfig.require("alpaca.api.secret")
    }
    val finnhubApiKey = if (needsMassive) LocalConfig.require("finnhub.api.key") else LocalConfig.get("finnhub.api.key").getOrElse("")
    val mongoUrl = LocalConfig.get("mongodb.url").getOrElse("mongodb://127.0.0.1:27017")

    val outboundHttpClient =
      (ZLayer.succeed(
        ZClient.Config.default
          .connectionTimeout(20.seconds)
          .idleTimeout(2.minutes)
      ) ++
        ZLayer.succeed(NettyConfig.default) ++
        DnsResolver.system) >>> Client.live

    val massiveLayer = MassiveAsyncApi.buildLayer
    val finnhubLayer = ZLayer.succeed(FinnhubClient.make(if (finnhubApiKey.isEmpty) "unused" else finnhubApiKey))
    val mongoClient = MongoClient.live(mongoUrl)
    val restServer = Server.defaultWithPort(7777)

    val everything = for {
      client <- ZIO.service[MongoClient]
      http <- ZIO.service[Client]
      store <- ZIO.fromEither(MarketStore.live(client)).mapError(msg => new IllegalStateException(msg))
      _ <- ZIO.logInfo(s"Enabled markets: ${store.enabled.map(_.id.id).mkString(", ")}")
      _ <- ZIO.logInfo(s"Enabled screens: ${screens.map(_.id).mkString(", ")}")
      _ <- ZIO.foreachParDiscard(store.enabled) { market =>
        startMarket(client, store, market, skipInitialization, skipCron)
      }
      allApi = ApiEndpoints.routes(store, http).toHttpApp
      mcpOrigins = LocalConfig.get("ttk.mcp.allowed.origins")
        .map(_.split(",").map(_.trim).filter(_.nonEmpty).toSet)
        .getOrElse(Set("http://localhost:7777", "http://127.0.0.1:7777", "http://[::1]:7777"))
      webSource = WebApp.describeSource
      _ <- ZIO.logInfo("Starting rest api")
      _ <- ZIO.logInfo(s"Serving web app from $webSource")
      _ <- ZIO
        .logWarning("Web app not found on disk or classpath. Run `sbt service/packageService` or `npm run build` in web/.")
        .when(webSource == "missing")
      _ <- Server.install(McpEndpoints.routes(allApi, mcpOrigins).toHttpApp ++ allApi ++ WebApp.app)
      _ <- ZIO.logInfo("Rest api started on port 7777")
      _ <- ZIO.logInfo("MCP Streamable HTTP endpoint started at /mcp")
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
      JquantsClient.live,
      EodhdClient.live
    )
  }

  private def startMarket(
      client: MongoClient,
      store: MarketStore,
      market: MarketDefinition,
      skipInitialization: Boolean,
      skipCron: Boolean
  ): ZIO[MassiveAsyncApi & BvbClient & XetraClient & JquantsClient & EodhdClient & AppHealth & FinnhubClient & Client & Scope, Throwable, Unit] = {
    val dbLayer = ZLayer.succeed(store.database(market))
    val screenerTask =
      if (market.id == Markets.UsAlpaca.id || market.id == Markets.CaEodhd.id) ScreenerServices.latestSessionScreenerTask
      else ScreenerServices.ScreenerTask
    val screenStoredQuotes = CronJobs
      .guarded(market.id.job("screens"), screenerTask)
      .provideSomeLayer[AppHealth](dbLayer)
      .forkDaemon
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
          // Rescreen first (SEPA quality gates), then repair quotes (local truncate + slow API resets).
          _ <- QuotesServices.hygieneThenRescreen
            .provideSomeLayer(dbLayer)
            .catchAll { error =>
              ZIO.logError(s"Startup quote hygiene/rescreen for ${market.id.id} failed: ${error.getMessage}")
            }
            .forkDaemon
          _ <- CronJobs.start(market).provideSomeLayer(dbLayer).unless(skipCron)
          _ <- ZIO.logInfo(s"Cron jobs started for ${market.id.id}").unless(skipCron)
        } yield ()
      case "alpaca" =>
        val financialsEnabled = LocalConfig.get("finnhub.api.key").isDefined
        val initialize = CronJobs.guarded(
          market.id.job("quotes"),
          AlpacaQuotesServices.initialize(market).tap { _ =>
            CronJobs.guarded(market.id.job("financials"), FiancialsServices.updateFinancials())
              .forkDaemon
              .when(financialsEnabled)
          }
        )
        for {
          _ <- ZIO.logInfo(s"Opening market ${market.id.id} database ${market.id.databaseName}")
          _ <- MongoDataRepo.ensureIndexes.provideLayer(dbLayer)
          hasUniverse <- MongoDataRepo.getStockUniverse
            .map(_.symbols.nonEmpty)
            .catchSome { case _: NoSuchElementException => ZIO.succeed(false) }
            .provideLayer(dbLayer)
          _ <- ZIO.logInfo(s"No stock universe for ${market.id.id}; starting first-time backfill")
            .unless(hasUniverse)
          _ <- initialize
            .provideSomeLayer(dbLayer)
            .forkDaemon
            .unless(skipInitialization && hasUniverse)
          _ <- screenStoredQuotes.when(skipInitialization && hasUniverse)
          _ <- CronJobs.startAlpaca(market, financialsEnabled)
            .provideSomeLayer(dbLayer)
            .unless(skipCron)
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
          _ <- screenStoredQuotes.when(skipInitialization)
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
          _ <- screenStoredQuotes.when(skipInitialization)
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
          _ <- screenStoredQuotes.when(skipInitialization)
          _ <- CronJobs.startJquants(market).provideSomeLayer(dbLayer).unless(skipCron)
          _ <- ZIO.logInfo(s"Cron jobs started for ${market.id.id}").unless(skipCron)
        } yield ()
      case "eodhd" =>
        for {
          _ <- ZIO.logInfo(s"Opening market ${market.id.id} database ${market.id.databaseName}")
          _ <- MongoDataRepo.ensureIndexes.provideLayer(dbLayer)
          _ <- CronJobs.guarded(market.id.job("quotes"), EodhdQuotesServices.initialize(market))
            .provideSomeLayer(dbLayer)
            .forkDaemon
            .unless(skipInitialization)
          _ <- screenStoredQuotes.when(skipInitialization)
          _ <- CronJobs.startEodhd(market).provideSomeLayer(dbLayer).unless(skipCron)
        } yield ()
      case other =>
        ZIO.fail(new IllegalStateException(s"No quote client for provider '$other'"))
    }
  }
}
