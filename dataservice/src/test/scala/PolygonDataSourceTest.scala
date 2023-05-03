package org.greensheep.trading.dataservice

import zio.{Console, Scope, ZIO}
import zio.test._


object PolygonDataSourceTest extends ZIOSpecDefault {


  val firstTest: Spec[Any, Throwable] = test("some test")(

    for {
      key <- zio.System.envOrElse("POLYGON_API_KEY", "")
      dataSource <- ZIO.succeed(new PolygonDataSource(key))
      symbols <- dataSource.getSymbols
    } yield assertTrue(symbols.nonEmpty)
  )

  val quotesTest: Spec[Any, Throwable] = test("quotes test")(
    for{
      key <- zio.System.envOrElse("POLYGON_API_KEY", "")
      dataSource <- ZIO.succeed(new PolygonDataSource(key))
      quotes <- dataSource.getQuotes("AAPL")
      _ <- Console.printLine(quotes.mkString("\n"))
    } yield assertTrue(quotes.nonEmpty)
  )

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("Polygon tests")(
      firstTest,quotesTest
    )
}
