package io.greensheep.trading.core.types

case class Market(
                 designator: String,
                 stocks: Map[String, Stock]
                 )
