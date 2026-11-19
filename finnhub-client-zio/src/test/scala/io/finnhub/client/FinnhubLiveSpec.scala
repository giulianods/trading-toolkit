package io.finnhub.client

import io.greensheep.trading.core.utils.LocalConfig
import zio.*
import zio.http.Client
import zio.test.*

object FinnhubLiveSpec extends ZIOSpecDefault {

  private val apiKey = LocalConfig.get("finnhub.api.key").orElse(sys.env.get("FINNHUB_API_KEY"))
  private val liveAspect =
    if (apiKey.isDefined && sys.env.get("FINNHUB_LIVE_TESTS").contains("true")) TestAspect.identity else TestAspect.ignore

  override def spec = suite("Finnhub live API")(
    test("retrieves AAPL and a sparse ticker") {
      ZIO.scoped {
        for {
          client <- FinnhubClient.make(apiKey.get)
          aapl <- client.basicFinancials("AAPL")
          ionq <- client.basicFinancials("IONQ")
        } yield assertTrue(
          aapl.symbol == "AAPL",
          aapl.series.annual.eps.nonEmpty,
          ionq.symbol == "IONQ"
        )
      }
    }.provide(Client.default),
    test("retrieves free as-reported annual and quarterly statements") {
      ZIO.scoped {
        for {
          client <- FinnhubClient.make(apiKey.get)
          annual <- client.financialsReported("ABM", "annual")
          quarterly <- client.financialsReported("ABM", "quarterly")
        } yield assertTrue(annual.get("data").isDefined, quarterly.get("data").isDefined)
      }
    }.provide(Client.default)
  ) @@ liveAspect @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(3.minutes)
}
