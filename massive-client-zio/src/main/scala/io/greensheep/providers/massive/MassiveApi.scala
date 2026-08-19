package io.greensheep.providers.massive

import io.greensheep.providers.massive.datatypes.{Aggregate, Ticker}

import java.time.LocalDate
import scala.concurrent.Future


abstract class MassiveApi(apiKey: String) {

  def getTickers: Future[Seq[Ticker]]

  def getAggregates(symbol: String, from: LocalDate, to: LocalDate): Future[Seq[Aggregate]]
}
