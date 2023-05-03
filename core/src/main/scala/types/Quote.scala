package org.greensheep.trading.core.types


import java.time.LocalDate

case class Quote(day:LocalDate, o: Double, h: Double, l: Double, c: Double, v: Int)
