package io.finnhub.client

import zio.*
import zio.http.Client
import zio.test.*

object FinnhubLiveSpec extends ZIOSpecDefault {

  private val liveAspect =
    if (sys.env.contains("FINNHUB_API_KEY")) TestAspect.identity else TestAspect.ignore

  override def spec = suite("Finnhub live API")(
    test("retrieves AAPL financials and US stock symbols") {
      ZIO.scoped {
        for {
          apiKey <- ZIO.succeed(sys.env("FINNHUB_API_KEY"))
          client = FinnhubClient.make(apiKey)
          financials <- client.basicFinancials("AAPL")
          symbols <- client.stockSymbols("US")
        } yield assertTrue(
          financials.symbol == "AAPL",
          symbols.exists(symbol => symbol.symbol == "AAPL" && symbol.mic.nonEmpty)
        )
      }
    }.provide(Client.default)
  ) @@ liveAspect
}
