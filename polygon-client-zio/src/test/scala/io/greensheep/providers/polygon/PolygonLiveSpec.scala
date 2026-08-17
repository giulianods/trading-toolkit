package io.greensheep.providers.polygon

import io.greensheep.providers.polygon.requests.AggregatesRequest
import zio.*
import zio.test.*

import java.time.LocalDate

object PolygonLiveSpec extends ZIOSpecDefault {

  private val liveAspect =
    if (sys.env.contains("POLYGON_API_KEY")) TestAspect.identity else TestAspect.ignore

  override def spec = suite("Polygon live API")(
    test("retrieves AAPL aggregates") {
      for {
        apiKey <- ZIO.succeed(sys.env("POLYGON_API_KEY"))
        end = LocalDate.now().minusYears(1)
        request = AggregatesRequest("AAPL", from = end.minusDays(14).toString, to = end.toString)
        aggregates <- new MarketClient(PolygonSettings(apiKey = apiKey)).getAggregates(request)
      } yield assertTrue(aggregates.nonEmpty)
    }
  ) @@ liveAspect
}
