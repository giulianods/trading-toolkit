package io.greensheep.trading.dataservice

import io.greensheep.providers.jquants.datatypes.JquantsBar
import zio.test.*

import java.time.LocalDate

object JquantsBridgeSpec extends ZIOSpecDefault {

  override def spec = suite("JquantsBridge")(
    test("converts J-Quants bars to core quotes") {
      val bar = JquantsBar("2024-06-03", open = 100, high = 110, low = 90, close = 105, volume = 1234)
      val quote = JquantsBridge.barToQuote(bar)
      assertTrue(
        quote.day == LocalDate.of(2024, 6, 3),
        quote.o == 100.0,
        quote.h == 110.0,
        quote.l == 90.0,
        quote.c == 105.0,
        quote.v == 1234
      )
    }
  )
}
