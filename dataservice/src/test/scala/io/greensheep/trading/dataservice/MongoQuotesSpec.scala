package io.greensheep.trading.dataservice

import io.greensheep.trading.core.types.Quote
import zio.test.*

import java.time.LocalDate

object MongoQuotesSpec extends ZIOSpecDefault {

  override def spec = suite("quote upsert-by-day")(
    test("replaces an existing day instead of duplicating it") {
      val day = LocalDate.of(2024, 6, 3)
      val existing = Seq(
        Quote(day.minusDays(1), 1, 2, 0.5, 1.5, 10),
        Quote(day, 10, 12, 9, 11, 100)
      )
      val incoming = Seq(Quote(day, 10.5, 12.5, 9.5, 11.5, 200))
      val merged = MongoDataRepo.mergeQuotes(existing, incoming)

      assertTrue(
        merged.size == 2,
        merged.last.c == 11.5,
        merged.last.v == 200,
        merged.head.day == day.minusDays(1)
      )
    }
  )
}
