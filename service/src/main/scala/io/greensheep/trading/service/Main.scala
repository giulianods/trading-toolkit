package io.greensheep.trading.service

import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.Quote
import io.greensheep.trading.dataservice.{LocalFileDataRepo, PolygonDataSource}
import zio.*

object Main extends ZIOAppDefault {

  def moveQuote(source: DataSource, repo: DataRepo, symbols: Iterator[String]): Task[Seq[Quote]] = {
    for{
      symbol <- ZIO.attempt(symbols.next())
      quotes <- source.getQuotes(symbol)
      saved <- repo.saveQuotes(symbol, quotes)
    } yield quotes
  }

  val downloadApp: ZIO[DataRepo with DataSource, Throwable, Unit] = for {
    symbols <- DataSource.getSymbols
    _ = println(s"Got ${symbols.length} symbols")
    schedule = Schedule.spaced(20.seconds)
    source <- ZIO.service[DataSource]
    repo <- ZIO.service[DataRepo]
    mover = moveQuote(source, repo, symbols.iterator)
    saveResult <- mover.repeat(schedule)

//    saveResult = symbols map { ticker: String =>
//      val result = for {
//        quotes <- DataSource.getQuotes(ticker).retry(schedule)
//        saved <- DataRepo.saveQuotes(ticker, quotes)
//      } yield saved
//      result
//    }
//    result <- saveResult
  } yield saveResult


  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    val layers: ZLayer[Any, Nothing, DataRepo with DataSource] = LocalFileDataRepo.layer ++ PolygonDataSource.layer
    downloadApp.provide(LocalFileDataRepo.layer ++ PolygonDataSource.layer)

  }
}