package io.greensheep.trading.dataservice

import io.greensheep.providers.jquants.datatypes.{JquantsBar, JquantsInstrumentInfo}
import io.greensheep.trading.core.types.{Quote, StockDescription}

import java.time.LocalDate

object JquantsBridge {

  def barToQuote(bar: JquantsBar): Quote =
    Quote(
      LocalDate.parse(bar.date),
      bar.open,
      bar.high,
      bar.low,
      bar.close,
      math.max(0, math.min(Int.MaxValue.toLong, bar.volume).toInt)
    )

  def toDescription(info: JquantsInstrumentInfo): StockDescription =
    StockDescription(
      symbol = info.ticker,
      companyName = info.name,
      ipoDate = LocalDate.of(1970, 1, 1),
      primaryExchange = "XTKS",
      description = info.description
    )
}
