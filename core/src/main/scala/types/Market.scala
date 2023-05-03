package org.greensheep.tradingtk
package core.types

case class Market(
                 designator: String,
                 stocks: Map[String, Stock]
                 )
