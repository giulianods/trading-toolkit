package io.greensheep.trading.core.types

import java.time.LocalDate

case class DailyAggregate(
                         o: Double,
                         h: Double,
                         l: Double,
                         c: Double,
                         v: Double,
                         t: LocalDate
                    )
