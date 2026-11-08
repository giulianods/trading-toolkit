package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.trading.core.types.{DailyQuoteData, MarketId, Markets, StockDescription, Watchlist}
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.AppHealth
import zio.*
import zio.http.{Client, HttpApp, Request, Status}
import zio.json.*
import zio.test.*

import java.time.LocalDate
import java.util.UUID

object AlpacaLiveIntegrationSpec extends ZIOSpecDefault {
  private val mongoUrl = sys.env.get("ALPACA_INTEGRATION_MONGODB_URL").filter(_.nonEmpty)
  private val tickers = Seq("AAPL", "MSFT")

  override def spec = suite("Alpaca live integration")(
    test("authenticates to assets and follows SIP bar pagination") {
      for {
        assets <- AlpacaClient.assets
        now <- Clock.instant
        end = AlpacaClient.latestSessionCandidate(now)
        bars <- AlpacaClient.bars(tickers, end.minusDays(10), end, now, pageSize = 2)
      } yield assertTrue(
        tickers.forall(ticker => assets.exists(asset => asset.symbol == ticker && asset.name.nonEmpty)),
        bars.keySet == tickers.toSet,
        bars.values.forall(quotes => quotes.size > 2 && quotes.map(_.day).distinct.size == quotes.size),
        bars.values.flatten.forall(quote => !quote.day.isAfter(end) && quote.h >= quote.l && quote.v > 0)
      )
    }.provide(Client.default),
    test("backfills an empty MongoDB database, refreshes without duplicate bars, and serves the results") {
      ZIO.scoped {
        for {
          client <- ZIO.service[MongoClient]
          http <- ZIO.service[Client]
          // A unique market ID keeps the production route lookup and Mongo database naming in use.
          market = Markets.UsAlpaca.copy(id = MarketId("us", s"alpaca-it-${UUID.randomUUID()}"))
          store = new MarketStore(client, Seq(market))
          db <- ZIO.acquireRelease(ZIO.succeed(store.database(market)))(_.drop().orDie)
          result <- (for {
            assets <- AlpacaClient.assets.map(_.filter(asset => tickers.contains(asset.symbol)))
            _ <- MongoDataRepo.ensureIndexes
            existed <- MongoDataRepo.hasStockUniverse
            _ <- AlpacaQuotesServices.refreshAssets(market, assets)
            initial <- MongoDataRepo.getPriceData("AAPL")
            _ <- AlpacaQuotesServices.refreshAssets(market, assets)
            universe <- MongoDataRepo.getStockUniverse
            stored <- MongoDataRepo.getPriceData("AAPL")
            latest <- MongoDataRepo.latestQuoteDate
            app = ApiEndpoints.routes(store, http).toHttpApp
            base = s"/markets/us/${market.id.provider}"
            quotes <- getJson[DailyQuoteData](app, s"$base/quotes/AAPL")
            watchlist <- getJson[Watchlist](app, s"$base/watchlists/SEPA")
            description <- getJson[StockDescription](app, s"$base/description/AAPL")
            freshness <- getJson[Map[String, LocalDate]](app, s"$base/data-status")
            breadth <- app.runZIO(Request.get(s"$base/breadth/indicators"))
            breadthBody <- breadth.body.asString
          } yield assertTrue(
            !existed,
            universe.symbols.toSet == tickers.toSet,
            initial.quotes.size > 200,
            stored.quotes.size >= initial.quotes.size,
            stored.quotes.map(_.day).distinct == stored.quotes.map(_.day),
            stored == quotes,
            latest.contains(universe.lastUpdateQuotes),
            freshness.get("latestQuoteDate") == latest,
            watchlist.day == universe.lastUpdateQuotes,
            watchlist.generatedAt.isDefined,
            description.companyName.nonEmpty,
            breadth.status == Status.Ok,
            breadthBody.contains(universe.lastUpdateQuotes.toString)
          )).provideSomeLayer[Client & AppHealth](ZLayer.succeed[MongoDatabase](db))
        } yield result
      }.provide(Client.default, AppHealth.layer, MongoClient.live(mongoUrl.getOrElse("mongodb://127.0.0.1:27017")))
    }
  ) @@ TestAspect.ifEnvSet("ALPACA_INTEGRATION_MONGODB_URL") @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)

  private def getJson[A: JsonDecoder](app: HttpApp[AppHealth], path: String): ZIO[AppHealth, Throwable, A] =
    for {
      response <- app.runZIO(Request.get(path))
      _ <- ZIO.fail(new IllegalStateException(s"$path returned ${response.status}")).unless(response.status == Status.Ok)
      body <- response.body.asString
      value <- ZIO.fromEither(body.fromJson[A]).mapError(message => new IllegalArgumentException(message))
    } yield value
}
