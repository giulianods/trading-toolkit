package io.greensheep.providers.polygon

import io.greensheep.providers.polygon.datatypes.{Aggregate, Ticker}

import java.time.LocalDate
import scala.concurrent.Future


abstract class PolygonApi(apiKey: String) {

  def getTickers: Future[Seq[Ticker]]

  def getAggregates(symbol: String, from: LocalDate, to: LocalDate): Future[Seq[Aggregate]]
}
