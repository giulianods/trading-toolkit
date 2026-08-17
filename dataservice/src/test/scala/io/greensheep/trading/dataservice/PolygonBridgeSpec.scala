package io.greensheep.trading.dataservice

import io.greensheep.providers.polygon.datatypes.Aggregate
import zio.test.*

import java.time.LocalDate

object PolygonBridgeSpec extends ZIOSpecDefault {

  override def spec = suite("PolygonBridge")(
    test("converts provider aggregates to core quotes") {
      val aggregate = Aggregate(c = 105.0, h = 106.0, l = 99.0, o = 100.0, t = 1704067200000L, v = 1234.0)
      val quote = PolygonBridge.aggregateToQuote(aggregate)

      assertTrue(
        quote.day == LocalDate.of(2024, 1, 1),
        quote.o == 100.0,
        quote.h == 106.0,
        quote.l == 99.0,
        quote.c == 105.0,
        quote.v == 1234
      )
    }
  )
}
