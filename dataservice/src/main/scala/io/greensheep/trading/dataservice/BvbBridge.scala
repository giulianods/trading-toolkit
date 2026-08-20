package io.greensheep.trading.dataservice

import io.greensheep.providers.bvb.datatypes.{BvbBar, BvbSymbolInfo}
import io.greensheep.trading.core.types.{Quote, StockDescription}

import java.time.{Instant, LocalDate, ZoneId}

object BvbBridge {
  private val Bucharest: ZoneId = ZoneId.of("Europe/Bucharest")

  def barToQuote(bar: BvbBar): Quote = {
    val day = Instant.ofEpochSecond(bar.time).atZone(Bucharest).toLocalDate
    Quote(day, bar.open, bar.high, bar.low, bar.close, math.max(0, bar.volume.toInt))
  }

  def toDescription(info: BvbSymbolInfo): StockDescription =
    StockDescription(
      symbol = info.ticker,
      companyName = info.name,
      ipoDate = LocalDate.of(1970, 1, 1),
      primaryExchange = "BVB",
      description = info.description
    )
}
