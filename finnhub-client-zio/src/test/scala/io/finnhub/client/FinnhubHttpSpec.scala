package io.finnhub.client

import zio.*
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object FinnhubHttpSpec extends ZIOSpecDefault {
  override def spec = suite("Finnhub HTTP requests")(
    test("preserves the documented query on the HTTP connection") {
      ZIO.scoped {
        for {
          port <- Server.install(Routes(
            (Method.GET / "probe") -> Handler.fromFunction[Request](request =>
              Response.json(Map("uri" -> request.url.encode).toJson))
          ).toHttpApp)
          client <- FinnhubClient.make("dummy")
          url <- client.endpoint("stock/financials-reported", "symbol" -> "AAP", "freq" -> "annual")
          result <- client.getAndDecode[Map[String, String]](
            url.scheme(Scheme.HTTP).host("127.0.0.1").port(port).path("probe"), "local request encoding")
        } yield assertTrue(result.get("uri").contains("/probe?symbol=AAP&freq=annual&token=dummy"))
      }.provide(Client.default, Server.defaultWithPort(0))
    }
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(20.seconds)
}
