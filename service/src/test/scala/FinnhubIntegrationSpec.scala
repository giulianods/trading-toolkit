package io.greensheep.trading.service.services

import io.finnhub.client.FinnhubClient
import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.trading.core.types.{DailyQuoteData, IncomeStatements, Markets, Watchlist}
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.AppHealth
import zio.*
import zio.http.{Client, Request, Status}
import zio.json.*
import zio.test.*

/** Run only against a disposable MongoDB; this test drops ttk-us-finnhub afterwards. */
object FinnhubIntegrationSpec extends ZIOSpecDefault {
  private val mongoUrl = sys.env.get("FINNHUB_INTEGRATION_MONGODB_URL")

  override def spec = suite("Finnhub live isolated integration")(
    test("backfills Alpaca bars, caches Finnhub statements, generates all screens and serves both APIs") {
      ZIO.scoped {
        for {
          mongo <- ZIO.service[MongoClient]
          http <- ZIO.service[Client]
          finnhub <- ZIO.service[FinnhubClient]
          market = Markets.UsFinnhub
          store = new MarketStore(mongo, Seq(market))
          db <- ZIO.acquireRelease(ZIO.succeed(store.database(market)))(_.drop().orDie)
          result <- (for {
            _ <- MongoDataRepo.ensureIndexes
            listings <- finnhub.stockSymbols()
            eligible = FinnhubServices.eligibleSymbols(listings).map(_.symbol).toSet
            assets <- AlpacaClient.assets.map(_.filter(asset => Seq("AAPL", "MSFT").contains(asset.symbol) && eligible.contains(asset.symbol)))
            _ <- ZIO.fail(new IllegalStateException("Missing AAPL/MSFT listings")).unless(assets.size == 2)
            _ <- AlpacaQuotesServices.refreshAssets(market, assets)
            _ <- FinnhubServices.refreshFinancials
            before <- MongoDataRepo.getCachedIncomeStatements("AAPL")
            _ <- FinnhubServices.refreshFinancials
            after <- MongoDataRepo.getCachedIncomeStatements("AAPL")
            universe <- MongoDataRepo.getStockUniverse
            _ <- InitializationService.initializeScreenHistory(universe.lastUpdateQuotes, rebuildExisting = true)
            app = ApiEndpoints.routes(store, http).toHttpApp
            financialResponse <- app.runZIO(Request.get("/markets/us/finnhub/income-statements/AAPL"))
            financialBody <- financialResponse.body.asString
            statements <- ZIO.fromEither(financialBody.fromJson[IncomeStatements]).mapError(new RuntimeException(_))
            quoteResponse <- app.runZIO(Request.get("/markets/us/finnhub/quotes/AAPL"))
            quoteBody <- quoteResponse.body.asString
            quotes <- ZIO.fromEither(quoteBody.fromJson[DailyQuoteData]).mapError(new RuntimeException(_))
            screens <- ZIO.foreach(ScreenerServices.screenOptions) { screen =>
              app.runZIO(Request.get(s"/markets/us/finnhub/watchlists/${screen.id}")).flatMap { response =>
                response.body.asString.flatMap(body => ZIO.fromEither(body.fromJson[Watchlist]).mapError(new RuntimeException(_)))
              }
            }
            _ <- ScreenerServices.latestSessionScreenerTask // same action as startup with backfill skipped
          } yield assertTrue(
            universe.dataSource == "finnhub", universe.symbols.toSet == Set("AAPL", "MSFT"),
            financialResponse.status == Status.Ok, quoteResponse.status == Status.Ok,
            statements.annual.exists(_.revenue.isDefined), statements.quarterly.size >= 6,
            statements.quarterly.exists(_.netMarginPct.isDefined),
            before.isDefined, before == after, quotes.quotes.size > 200,
            screens.map(_.screenName) == ScreenerServices.screenOptions.map(_.id),
            screens.forall(_.day == universe.lastUpdateQuotes), screens.forall(_.generatedAt.isDefined)
          )).provideSomeLayer[Client & FinnhubClient & AppHealth & Scope](ZLayer.succeed[MongoDatabase](db))
        } yield result
      }.provide(Client.default, AppHealth.layer,
        ZLayer.fromZIO(ZIO.attempt(LocalConfig.require("finnhub.api.key")).flatMap(FinnhubClient.make)),
        MongoClient.live(mongoUrl.getOrElse("mongodb://127.0.0.1:27017")))
    }
  ) @@ TestAspect.ifEnvSet("FINNHUB_INTEGRATION_MONGODB_URL") @@ TestAspect.withLiveClock @@ TestAspect.timeout(5.minutes)
}
