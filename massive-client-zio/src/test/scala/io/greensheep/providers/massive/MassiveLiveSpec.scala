package io.greensheep.providers.massive

import io.greensheep.providers.massive.requests.AggregatesRequest
import zio.*
import zio.test.*

import java.time.LocalDate

object MassiveLiveSpec extends ZIOSpecDefault {

  private val liveAspect =
    if (sys.env.contains("MASSIVE_API_KEY")) TestAspect.identity else TestAspect.ignore

  override def spec = suite("Massive live API")(
    test("retrieves AAPL aggregates") {
      for {
        apiKey <- ZIO.succeed(sys.env("MASSIVE_API_KEY"))
        end = LocalDate.now().minusYears(1)
        request = AggregatesRequest("AAPL", from = end.minusDays(14).toString, to = end.toString)
        aggregates <- new MarketClient(MassiveSettings(apiKey = apiKey)).getAggregates(request)
      } yield assertTrue(aggregates.nonEmpty)
    }
  ) @@ liveAspect
}
