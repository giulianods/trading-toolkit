package org.greensheep.trading.dataservice

import org.greensheep.trading.dataservice.Test2.test
import zhttp.service.{ChannelFactory, Client, EventLoopGroup}
import zio._
import zio.test.{test, _}
import zio.test.Assertion._

import java.nio.charset.StandardCharsets

object Test2 extends ZIOSpecDefault {
  val env = ChannelFactory.auto ++ EventLoopGroup.auto()

  def spec = suite("spec")(
    test("one"){
      val dataSource = new FinnhubDataSource()
      for {
        symbols <- dataSource.getSymbols
        _ <- Console.printLine(symbols.mkString("\n"))

      } yield assertTrue(true)
    },

    test ("two") {
      val dataSource = new FinnhubDataSource()
      for {
        stock <- dataSource.getStock("AAPL")
        _ <- Console.printLine(stock.toString)

      } yield assertTrue(true)
    }
  ).provideLayer(env)

}
