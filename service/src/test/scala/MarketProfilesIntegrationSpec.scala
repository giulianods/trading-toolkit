package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.trading.core.types.*
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo, StockUniverse}
import io.greensheep.trading.service.app.AppHealth
import zio.*
import zio.http.{Client, Request, Status}
import zio.json.*
import zio.test.*
import java.time.{Instant, LocalDate}

/** Only against a disposable MongoDB: drops all four source/profile databases afterwards. */
object MarketProfilesIntegrationSpec extends ZIOSpecDefault {
  private val mongoUrl = sys.env.get("MARKET_PROFILE_TEST_MONGODB_URL")
  private val day = LocalDate.parse("2026-11-13")
  private def statements(revenue: Double) = IncomeStatements(Seq(IncomePeriod(day, Some("USD"), Some(revenue), Some(10), Some(10), Some(1))), Seq.empty)

  override def spec = suite("Composed market profiles")(
    test("isolates price feeds, shares selected fundamentals, switches financial sources and preserves legacy routes") {
      ZIO.scoped {
        for {
          mongo <- ZIO.service[MongoClient]
          http <- ZIO.service[Client]
          profiles <- ZIO.fromEither(Markets.configured(Seq("us-massive", "us-alpaca"), _ => None,
            key => Option.when(key == "ttk.profiles.us-alpaca.aliases")(Seq("us-finnhub"))))
            .mapError(new RuntimeException(_))
          store = new MarketStore(mongo, profiles)
          massive <- ZIO.acquireRelease(ZIO.succeed(store.database(MarketId.UsMassive)))(_.drop().orDie)
          alpaca <- ZIO.acquireRelease(ZIO.succeed(store.database(MarketId.UsAlpaca)))(_.drop().orDie)
          finnhub <- ZIO.acquireRelease(ZIO.succeed(store.financialsDatabase(profiles.head)))(_.drop().orDie)
          eodhd <- ZIO.acquireRelease(ZIO.succeed(store.database(MarketId("us", "eodhd"))))(_.drop().orDie)
          _ <- ZIO.foreachDiscard(Seq(profiles.head -> massive, profiles.last -> alpaca)) { case (profile, db) =>
            (MongoDataRepo.saveStockUniverse(StockUniverse("stock-universe", "stock-universe", profile.sources.prices,
              day, LocalDate.ofEpochDay(0), profile.timeZone, Seq("AAPL"))) *>
              MongoDataRepo.replaceDailyQuotes("AAPL", Seq(Quote(day, 10, 30, 5, if (profile.sources.prices == "massive") 11 else 22, 100))) *>
              MongoDataRepo.saveStockDescription(StockDescription("AAPL", profile.label, day,
                description = if (profile.sources.prices == "massive") "Shared company narrative" else "Old unrelated narrative"))).provide(ZLayer.succeed(db))
          }
          _ <- MongoDataRepo.cacheIncomeStatements(MongoDataRepo.CachedIncomeStatements("AAPL", Instant.now(), statements(100)))
            .provide(ZLayer.succeed(finnhub))
          _ <- MongoDataRepo.cacheIncomeStatements(MongoDataRepo.CachedIncomeStatements("AAPL", Instant.now(), statements(200)))
            .provide(ZLayer.succeed(eodhd))
          app = ApiEndpoints.routes(store, http).toHttpApp
          financials <- ZIO.foreach(Seq("massive", "alpaca", "finnhub")) { profile =>
            app.runZIO(Request.get(s"/markets/us/$profile/income-statements/AAPL"))
              .flatMap(_.body.asString).flatMap(body => ZIO.fromEither(body.fromJson[IncomeStatements]).mapError(new RuntimeException(_)))
          }
          prices <- ZIO.foreach(Seq("/quotes/AAPL", "/markets/us/alpaca/quotes/AAPL", "/markets/us/finnhub/quotes/AAPL")) { path =>
            app.runZIO(Request.get(path)).flatMap(_.body.asString)
              .flatMap(body => ZIO.fromEither(body.fromJson[DailyQuoteData]).mapError(new RuntimeException(_)))
          }
          configured <- app.runZIO(Request.get("/markets")).flatMap(_.body.asString)
          metadata <- app.runZIO(Request.get("/markets/us/alpaca/description/AAPL")).flatMap(_.body.asString)
            .flatMap(body => ZIO.fromEither(body.fromJson[StockDescription]).mapError(new RuntimeException(_)))
          alternate = profiles.last.copy(providers = Some(profiles.last.sources.copy(financials = "eodhd")))
          alternateApp = ApiEndpoints.routes(new MarketStore(mongo, Seq(alternate)), http).toHttpApp
          changed <- alternateApp.runZIO(Request.get("/markets/us/alpaca/income-statements/AAPL")).flatMap(_.body.asString)
            .flatMap(body => ZIO.fromEither(body.fromJson[IncomeStatements]).mapError(new RuntimeException(_)))
          noFinancials = alternate.copy(providers = Some(alternate.sources.copy(financials = "none")))
          disabledApp = ApiEndpoints.routes(new MarketStore(mongo, Seq(noFinancials)), http).toHttpApp
          disabled <- disabledApp.runZIO(Request.get("/markets/us/alpaca/income-statements/AAPL")).flatMap(_.body.asString)
          unknown <- app.runZIO(Request.get("/markets/us/unknown/income-statements/AAPL"))
        } yield assertTrue(financials.forall(_.annual.head.revenue.contains(100)),
          prices.map(_.quotes.last.c) == Seq(11, 22, 22),
          configured.contains("US · Massive + Finnhub"), configured.contains("US · Alpaca + Finnhub"), configured.contains("us-finnhub"),
          metadata.description == "Shared company narrative", metadata.companyName == profiles.last.label,
          changed.annual.head.revenue.contains(200), disabled.fromJson[IncomeStatements].contains(IncomeStatements.empty),
          unknown.status == Status.NotFound)
      }.provide(MongoClient.live(mongoUrl.getOrElse("mongodb://127.0.0.1:1")), Client.default, AppHealth.layer)
    }
  ) @@ TestAspect.ifEnvSet("MARKET_PROFILE_TEST_MONGODB_URL") @@ TestAspect.withLiveClock @@ TestAspect.timeout(90.seconds)
}
