package io.greensheep.trading.dataservice

import io.greensheep.providers.bvb.datatypes.BvbBar
import zio.test.*

import java.time.{Instant, LocalDate}

object BvbBridgeSpec extends ZIOSpecDefault {

  override def spec = suite("BvbBridge")(
    test("converts BVB bars to core quotes") {
      val bar = BvbBar(time = Instant.parse("2024-06-03T14:00:00Z").getEpochSecond, open = 10, high = 12, low = 9, close = 11, volume = 1234)
      val quote = BvbBridge.barToQuote(bar)
      assertTrue(
        quote.day == LocalDate.of(2024, 6, 3),
        quote.o == 10.0,
        quote.h == 12.0,
        quote.l == 9.0,
        quote.c == 11.0,
        quote.v == 1234
      )
    }
  )
}
