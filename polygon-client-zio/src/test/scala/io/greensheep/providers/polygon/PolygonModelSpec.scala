package io.greensheep.providers.polygon

import io.greensheep.providers.polygon.datatypes.{Aggregate, ApiResponse}
import io.greensheep.providers.polygon.requests.AggregatesRequest
import zio.json.*
import zio.http.URL
import zio.test.*

object PolygonModelSpec extends ZIOSpecDefault {

  override def spec = suite("Polygon client models")(
    test("aggregate requests include all query parameters") {
      val url = AggregatesRequest(
        stocksTicker = "AAPL",
        from = "2024-01-01",
        to = "2024-02-01",
        adjusted = false,
        sort = "desc",
        limit = 1000
      ).buildRequest("https://api.polygon.io")

      assertTrue(
        url == "https://api.polygon.io/v2/aggs/ticker/AAPL/range/1/day/2024-01-01/2024-02-01?adjusted=false&sort=desc&limit=1000"
      )
    },
    test("authentication adds the API key query parameter") {
      val url = URL.decode("https://api.polygon.io/v2/aggs?ticker=AAPL").toOption.get
      val authenticated = PolygonSettings(apiKey = "dummy-token").authenticate(url)

      assertTrue(authenticated.queryParams.get("apiKey").contains("dummy-token"))
    },
    test("client base authentication survives path construction") {
      val base = PolygonSettings(apiKey = "dummy-token")
        .authenticate(URL.decode("https://api.polygon.io").toOption.get)
      val requestUrl = base / "v2" / "aggs" / "ticker" / "AAPL"

      assertTrue(requestUrl.queryParams.get("apiKey").contains("dummy-token"))
    },
    test("decodes aggregate responses") {
      val json =
        """{"next_url":null,"results":[{"c":190.0,"h":191.0,"l":188.0,"o":189.0,"t":1704067200000,"v":12345.0}],"status":"OK","error":null}"""
      val decoded = json.fromJson[ApiResponse[Seq[Aggregate]]]

      assertTrue(
        decoded.exists(_.isValid),
        decoded.toOption.flatMap(_.results).exists(_.head.c == 190.0)
      )
    }
  )
}
