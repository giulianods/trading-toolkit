package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.trading.core.types.{MarketId, Markets, PortfolioTrade, Quote}
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.AppHealth
import zio.*
import zio.http.*
import zio.json.*
import zio.test.*
import java.time.{Instant, LocalDate}
import java.util.UUID

object ResearchIntegrationSpec extends ZIOSpecDefault {
  private val mongoUrl = sys.env.getOrElse("RESEARCH_TEST_MONGODB_URL", "mongodb://127.0.0.1:1")
  private def post(path: String, json: String) = Request.post(path, Body.fromString(json))
    .copy(headers = Headers(Header.Custom("Content-Type", "application/json")))
  private def decode[A: JsonDecoder](response: Response): Task[A] = response.body.asString
    .flatMap(s => ZIO.fromEither(s.fromJson[A]).mapError(new RuntimeException(_)))

  override def spec = suite("Reports and portfolio persistence")(
    test("REST/MCP writes persist, isolate markets, reject invalid fills and safely retry concurrent updates") {
      ZIO.scoped {
        for {
          mongo <- ZIO.service[MongoClient]
          http <- ZIO.service[Client]
          suffix = UUID.randomUUID.toString
          us = Markets.UsMassive.copy(id = MarketId("us", s"test-$suffix"))
          ro = Markets.RoBvb.copy(id = MarketId("ro", s"test-$suffix"))
          store = new MarketStore(mongo, Seq(us, ro))
          db <- ZIO.acquireRelease(ZIO.succeed(store.database(us)))(_.drop().orDie)
          _ <- ZIO.acquireRelease(ZIO.succeed(store.database(ro)))(_.drop().orDie)
          app = ApiEndpoints.routes(store, http).toHttpApp
          mcp = McpEndpoints.routes(app).toHttpApp
          path = s"/markets/us/test-$suffix"
          roPath = s"/markets/ro/test-$suffix"
          empty <- app.runZIO(Request.get(s"$path/portfolio")).flatMap(decode[ResearchServices.PortfolioView])
          report1 <- app.runZIO(post(s"$path/reports", """{"title":"First","markdown":"# First","asOf":"2026-11-01"}"""))
          _ <- ZIO.sleep(2.millis)
          rpc = s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"post_markets_country_provider_reports","arguments":{"country":"us","provider":"test-$suffix","body":{"title":"Second","markdown":"## Latest","asOf":"2026-11-02"}}}}"""
          report2 <- mcp.runZIO(post("/mcp", rpc).copy(headers = Headers(Header.Custom("Content-Type", "application/json"), Header.Custom("Accept", "application/json, text/event-stream"))))
          rpcBody <- report2.body.asString
          reports <- app.runZIO(Request.get(s"$path/reports")).flatMap(decode[Seq[ResearchServices.ReportView]])
          detail <- app.runZIO(Request.get(s"$path/reports/${reports.head.id}")).flatMap(decode[ResearchServices.ReportView])
          otherReports <- app.runZIO(Request.get(s"$roPath/reports")).flatMap(decode[Seq[ResearchServices.ReportView]])
          buy = PortfolioTrade("fill-1", "ABC", "buy", 10, 100, Instant.parse("2026-11-01T12:00:00Z"), 2)
          fills <- ZIO.foreachPar(1 to 4)(_ => app.runZIO(post(s"$path/portfolio/trades", buy.toJson)))
          conflict <- app.runZIO(post(s"$path/portfolio/trades", buy.copy(price = 101).toJson))
          oversell <- app.runZIO(post(s"$path/portfolio/trades", buy.copy(id = "oversell", side = "sell", quantity = 11).toJson))
          tooEarly <- app.runZIO(post(s"$path/portfolio/trades", buy.copy(id = "early", executedAt = buy.executedAt.minusSeconds(1)).toJson))
          invalid <- app.runZIO(post(s"$path/reports", """{"title":"","markdown":"","asOf":"no"}"""))
          _ <- MongoDataRepo.replaceDailyQuotes("ABC", Seq(Quote(LocalDate.parse("2026-11-02"), 120, 120, 120, 120, 100))).provide(ZLayer.succeed(db))
          snapshot <- app.runZIO(Request.get(s"$path/portfolio")).flatMap(decode[ResearchServices.PortfolioView])
          other <- app.runZIO(Request.get(s"$roPath/portfolio")).flatMap(decode[ResearchServices.PortfolioView])
          reloaded <- ApiEndpoints.routes(new MarketStore(mongo, Seq(us)), http).toHttpApp.runZIO(Request.get(s"$path/portfolio")).flatMap(decode[ResearchServices.PortfolioView])
        } yield assertTrue(empty.cash == 100000, empty.trades.isEmpty,
          report1.status == Status.Created, rpcBody.contains("Second"), !rpcBody.contains("\"isError\":true"),
          reports.map(_.title) == Seq("Second", "First"), detail.markdown == "## Latest", otherReports.isEmpty,
          fills.count(_.status == Status.Created) == 1, fills.count(_.status == Status.Ok) == 3,
          conflict.status == Status.Conflict, oversell.status == Status.BadRequest, tooEarly.status == Status.BadRequest,
          invalid.status == Status.BadRequest, snapshot.trades.size == 1, snapshot.cash == 98998,
          snapshot.equity == 100198, snapshot.positions.head.currentPrice == 120,
          other.trades.isEmpty, other.cash == 100000, other.currency == "RON", reloaded == snapshot)
      }.provide(MongoClient.live(mongoUrl), Client.default, AppHealth.layer)
    }
  ) @@ TestAspect.ifEnvSet("RESEARCH_TEST_MONGODB_URL") @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
