package io.greensheep.trading.dataservice

import io.greensheep.providers.polygon.datatypes.Aggregate
import io.greensheep.trading.core.types.Quote

object PolygonBridge {

  def aggregateToQuote(agg: Aggregate) = {
    Quote(agg.timestamp.toLocalDate, agg.o, agg.h, agg.l, agg.c, agg.v.toInt)
  }
}
