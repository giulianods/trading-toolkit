package org.greensheep.trading.dataservice
package io.greensheep.trading.dataservice

import org.scalatest.funsuite.AnyFunSuite
import zio._
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}

class PolygonSourceFactoryTest extends AnyFunSuite{

  val apiKey: String =  ""
  val factory = new PolygonSourceFactory(apiKey)

  test("some test"){
    val task = factory.buildGetSymbols()

    val newTask = task.map(s => s.map(t => println(t)))
    println("New task created")

    Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(
        newTask
      ).getOrThrowFiberFailure()
    }

  }
}
