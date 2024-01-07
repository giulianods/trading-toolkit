package io.greensheep.trading.core.services


import io.greensheep.trading.core.types.Quote
import zio.{Task, ZIO}

object DataRepo {
  def saveQuotes(symbol: String, quotes: Seq[Quote]): ZIO[DataRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[DataRepo](_.saveQuotes(symbol, quotes))
}

trait DataRepo extends DataSource {


  def saveQuotes(symbol: String, quotes: Seq[Quote]): Task[Unit]
}
