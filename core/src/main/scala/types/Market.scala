package org.greensheep.trading
package core.types

case class Market(
                 designator: String,
                 stocks: Map[String, Stock]
                 )
