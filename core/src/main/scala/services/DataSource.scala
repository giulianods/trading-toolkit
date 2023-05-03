package org.greensheep.trading.core
package services

import org.greensheep.trading.core.types.{Quote, Stock}
import zio.{IO, Task, ZIO}

object DataSource{

  def getSymbols: ZIO[DataSource, Throwable, Seq[String]] =
    ZIO.serviceWithZIO[DataSource](_.getSymbols)

  def getQuotes(ticker: String): ZIO[DataSource, Throwable, Seq[Quote]] =
    ZIO.serviceWithZIO[DataSource](_.getQuotes(ticker))
}


trait DataSource {

  def getSymbols: Task[Seq[String]]

  def getStock(symbol: String): IO[Throwable, Stock]

  def getStocks(): Map[String,Stock]

  def getQuotes(symbol: String): Task[Seq[Quote]]

}
