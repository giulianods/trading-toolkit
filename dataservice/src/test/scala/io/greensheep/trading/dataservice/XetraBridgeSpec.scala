package io.greensheep.trading.dataservice

import io.greensheep.providers.xetra.datatypes.XetraBar
import zio.test.*

import java.time.LocalDate

object XetraBridgeSpec extends ZIOSpecDefault {

  override def spec = suite("XetraBridge")(
    test("converts Xetra bars to core quotes in Berlin calendar") {
      val bar = XetraBar("2024-06-03", open = 10, high = 12, low = 9, close = 11, volume = 1234)
      val quote = XetraBridge.barToQuote(bar)
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
