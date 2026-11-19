package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.trading.core.types.{MarketId, Markets, StockDescription}
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.AppHealth
import zio.*
import zio.http.{Client, Request, Status}
import zio.json.*
import zio.test.*
import java.time.LocalDate

/** Explicitly opt in against a disposable MongoDB; all three test market databases are dropped. */
object CompanyDescriptionIntegrationSpec extends ZIOSpecDefault {
  private val mongoUrl = sys.env.get("COMPANY_METADATA_TEST_MONGODB_URL")
  override def spec = suite("Company metadata isolated integration")(
    test("REST composes configured metadata without mixing price identities or copying the source cache") {
      ZIO.scoped {
        for {
          mongo <- ZIO.service[MongoClient]
          http <- ZIO.service[Client]
          localAlpaca = Markets.UsAlpaca.copy(providers = Some(Markets.UsAlpaca.sources.copy(metadata = "alpaca")))
          store = new MarketStore(mongo, Seq(Markets.UsFinnhub, Markets.UsMassive, localAlpaca))
          target <- ZIO.acquireRelease(ZIO.succeed(store.database(MarketId.UsFinnhub)))(_.drop().orDie)
          source <- ZIO.acquireRelease(ZIO.succeed(store.database(MarketId.UsMassive)))(_.drop().orDie)
          alpaca <- ZIO.acquireRelease(ZIO.succeed(store.database(MarketId.UsAlpaca)))(_.drop().orDie)
          base = StockDescription("AAPL", "Apple listing", LocalDate.ofEpochDay(0), primaryExchange = "NASDAQ")
          ref = base.copy(description = "Apple makes consumer electronics.", sicCode = "3571", sicDescription = "Electronic Computers")
          _ <- MongoDataRepo.saveStockDescription(base).provide(ZLayer.succeed(target))
          _ <- MongoDataRepo.saveStockDescription(base).provide(ZLayer.succeed(alpaca))
          _ <- MongoDataRepo.saveStockDescription(base.copy(symbol = "EMPTY")).provide(ZLayer.succeed(target))
          _ <- MongoDataRepo.saveStockDescription(ref).provide(ZLayer.succeed(source))
          _ <- MongoDataRepo.saveStockDescription(ref.copy(symbol = "UNKNOWN")).provide(ZLayer.succeed(source))
          app = ApiEndpoints.routes(store, http).toHttpApp
          labelResponse <- app.runZIO(Request.get(zio.http.URL.decode(
            "/markets/us/finnhub/descriptions?tickers=AAPL,EMPTY,UNKNOWN").toOption.get))
          labels <- labelResponse.body.asString
          response <- app.runZIO(Request.get("/markets/us/finnhub/description/AAPL"))
          body <- response.body.asString
          parsed <- ZIO.fromEither(body.fromJson[StockDescription]).mapError(new RuntimeException(_))
          saved <- MongoDataRepo.getStockDescription("AAPL").provide(ZLayer.succeed(target))
          _ <- MongoDataRepo.updateStockNarrative(parsed).provide(ZLayer.succeed(target))
          // Simulate a quote refresh holding an older, unenriched snapshot.
          _ <- MongoDataRepo.updateStockListingMetadata(base.copy(companyName = "Updated listing"))
            .provide(ZLayer.succeed(target))
          afterQuote <- MongoDataRepo.getStockDescription("AAPL").provide(ZLayer.succeed(target))
          _ <- MongoDataRepo.updateStockNarrative(parsed).provide(ZLayer.succeed(target))
          afterNarrative <- MongoDataRepo.getStockDescription("AAPL").provide(ZLayer.succeed(target))
          unavailable <- app.runZIO(Request.get("/markets/us/finnhub/description/EMPTY")).flatMap(_.body.asString)
          unknown <- app.runZIO(Request.get("/markets/us/finnhub/description/UNKNOWN"))
          other <- app.runZIO(Request.get("/markets/us/alpaca/description/AAPL")).flatMap(_.body.asString)
          alias <- app.runZIO(Request.get("/description/AAPL")).flatMap(_.body.asString)
        } yield assertTrue(response.status == Status.Ok, parsed.description == ref.description,
          parsed.companyName == base.companyName, parsed.primaryExchange == "NASDAQ", saved == base,
          afterQuote.description == ref.description, afterNarrative.companyName == "Updated listing",
          labelResponse.status == Status.Ok, labels.contains("Electronic Computers"), !labels.contains("UNKNOWN"),
          unavailable.fromJson[StockDescription].exists(_.description.isEmpty), unknown.status == Status.NotFound,
          other.fromJson[StockDescription].exists(_.description.isEmpty), alias.fromJson[StockDescription].contains(ref))
      }.provide(MongoClient.live(mongoUrl.getOrElse("mongodb://127.0.0.1:1")), Client.default, AppHealth.layer)
    }
  ) @@ TestAspect.ifEnvSet("COMPANY_METADATA_TEST_MONGODB_URL") @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
