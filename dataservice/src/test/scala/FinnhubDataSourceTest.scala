package org.greensheep.tradingtk.dataservice

import cats.effect.IO
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.must.Matchers.be
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import zhttp.http.Headers
import zhttp.service.{ChannelFactory, Client, EventLoopGroup}
import zio.ZIO
import zio.ZIO.console

import java.nio.charset.StandardCharsets




class FinnhubDataSourceTest extends AnyFunSuite {


  test("test read api key"){
    val apiKey = FinnhubDataSource.API_KEY
    apiKey shouldNot be("")
  }

  test("test get quotes"){
    val dataSource = new FinnhubDataSource()
    dataSource.getQuotes("AAPL")
  }

  test("client"){

    val env = ChannelFactory.auto ++ EventLoopGroup.auto()

    val program = for {
      _ <- ZIO.log("Start")
      res <- Client.request("http://sports.api.decathlon.com/groups/water-aerobics")
      _ <- ZIO.log("Finish")
      success <- ZIO.blocking(res.body.asString(StandardCharsets.UTF_8))
      _ <- ZIO.log(success)
    } yield ()

    program.exitCode.provideSomeLayer(env)

  }

}
