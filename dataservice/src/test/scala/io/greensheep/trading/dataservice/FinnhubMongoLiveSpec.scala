package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.finnhub.client.FinnhubClient
import io.greensheep.trading.core.utils.LocalConfig
import zio.*
import zio.http.Client
import zio.test.*

object FinnhubMongoLiveSpec extends ZIOSpecDefault {

  private val apiKey = LocalConfig.get("finnhub.api.key").orElse(sys.env.get("FINNHUB_API_KEY"))
  private val mongoUrl = LocalConfig.get("mongodb.url").getOrElse("mongodb://127.0.0.1:27017")
  private val liveAspect =
    if (apiKey.isDefined) TestAspect.identity else TestAspect.ignore

  private val mongoLayer =
    MongoClient.live(mongoUrl) >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("polygon")))

  override def spec = suite("Finnhub live Mongo persist")(
    test("persists AAPL financials into the polygon database") {
      ZIO.scoped {
        for {
          client <- ZIO.succeed(FinnhubClient.make(apiKey.get))
          raw <- client.basicFinancials("AAPL")
          annual = FinnhubBridge.translateAnnualFinancials(raw)
          quarterly = FinnhubBridge.translateQuarterlyFinancials(raw)
          _ <- MongoDataRepo.updateFinancials("AAPL", annual, quarterly)
          stored <- MongoDataRepo.getFinancials("AAPL")
        } yield assertTrue(
          stored.ticker == "AAPL",
          stored.annual.nonEmpty,
          stored.annual.exists(_.basicEarningsPerShare > 0)
        )
      }
    }.provide(Client.default ++ mongoLayer)
  ) @@ liveAspect
}
