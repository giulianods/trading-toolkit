package org.greensheep.trading.dataservice

import zio.{Console, Scope, ZIO}
import zio.test._

object PolygonDataSourceTest extends ZIOSpecDefault {


  val firstTest: Spec[Any, Throwable] = test("some test")(

    for {
      dataSource <- ZIO.succeed(new PolygonDataSource(""))
      symbols <- dataSource.getSymbols
      _ <- Console.printLine(symbols.mkString("\n"))
    } yield assertTrue(symbols.isEmpty)
  )

  val quotesTest: Spec[Any, Throwable] = test("quotes test")(
    for{
      dataSource <- ZIO.succeed(new PolygonDataSource(""))
      quotes <- dataSource.getQuotes("AAPL")
      _ <- Console.printLine(quotes.mkString("\n"))
    } yield assertTrue(quotes.nonEmpty)
  )

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("Polygon tests")(
      firstTest,quotesTest
    )
}
