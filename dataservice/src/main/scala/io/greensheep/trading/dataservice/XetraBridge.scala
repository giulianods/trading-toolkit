package io.greensheep.trading.dataservice

import io.greensheep.providers.xetra.datatypes.XetraBar
import io.greensheep.providers.xetra.datatypes.XetraInstrumentInfo
import io.greensheep.trading.core.types.{Quote, StockDescription}

import java.time.LocalDate

object XetraBridge {

  def barToQuote(bar: XetraBar): Quote = {
    val day = LocalDate.parse(bar.date)
    Quote(day, bar.open, bar.high, bar.low, bar.close, math.max(0, bar.volume.toInt))
  }

  def toDescription(info: XetraInstrumentInfo): StockDescription =
    StockDescription(
      symbol = info.ticker,
      companyName = info.name,
      ipoDate = LocalDate.of(1970, 1, 1),
      primaryExchange = "XETR",
      description = info.description
    )
}
