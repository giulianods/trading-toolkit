package io.greensheep.trading.service

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.bvb.BvbClient
import io.greensheep.providers.eodhd.EodhdClient
import io.greensheep.providers.jquants.JquantsClient
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.providers.xetra.XetraClient
import io.greensheep.trading.core.types.Markets
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.MarketStore
import io.greensheep.trading.service.app.{AppHealth, WebApp}
import io.greensheep.trading.service.services.{ApiEndpoints, MarketDataServices, McpEndpoints, ScreenerServices}
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
    val needsMassive = enabled.exists(m => m.sources.prices == "massive" || m.sources.listings == "massive")
    val needsJquants = enabled.exists(_.sources.prices == "jquants")
    if (needsMassive) LocalConfig.require("massive.api.key")
    if (needsJquants) LocalConfig.require("jquants.api.key")
    if (enabled.exists(_.sources.prices == "eodhd") && (!skipInitialization || !skipCron)) LocalConfig.require("eodhd.api.key")
    if (enabled.exists(_.sources.prices == "alpaca")) {
      LocalConfig.require("alpaca.api.key")
      LocalConfig.require("alpaca.api.secret")
    }
    val finnhubApiKey = if (enabled.exists(m => m.sources.financials == "finnhub" || m.sources.listings == "finnhub")) LocalConfig.require("finnhub.api.key") else LocalConfig.get("finnhub.api.key").getOrElse("")
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
    val finnhubLayer = ZLayer.fromZIO(FinnhubClient.make(if (finnhubApiKey.isEmpty) "unused" else finnhubApiKey))
    val mongoClient = MongoClient.live(mongoUrl)
    val restServer = Server.defaultWithPort(7777)

    val everything = for {
      client <- ZIO.service[MongoClient]
      http <- ZIO.service[Client]
      store <- ZIO.fromEither(MarketStore.live(client)).mapError(msg => new IllegalStateException(msg))
      _ <- ZIO.logInfo(s"Enabled markets: ${store.enabled.map(_.id.id).mkString(", ")}")
      _ <- ZIO.logInfo(s"Enabled screens: ${screens.map(_.id).mkString(", ")}")
      services <- MarketDataServices.make(client, store)
      _ <- ZIO.foreachParDiscard(store.enabled) { market =>
        services.start(market, skipInitialization, skipCron)
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

}
