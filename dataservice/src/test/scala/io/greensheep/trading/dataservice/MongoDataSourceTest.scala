package io.greensheep.trading.dataservice

import io.greensheep.trading.dataservice.PolygonDataSourceTest.test
import zio.{Scope, ZIO}
import zio.test.{Spec, ZIOSpecDefault}
import zio.test.*

object MongoDataSourceTest extends ZIOSpecDefault{

  val firstTest: Spec[MongoDbDataRepo, Throwable] = test("some test")(
    for {
      repo <- ZIO.service[MongoDbDataRepo]

      _ <- ZIO.succeed(repo.updateAggregates("AAPL", Seq.empty))
    } yield assertTrue(true)
  )


  override def spec: Spec[TestEnvironment, Any] =
    suite("Mongo tests")(
      firstTest
    ).provideLayer(MongoDbDataRepo.layer)
}
