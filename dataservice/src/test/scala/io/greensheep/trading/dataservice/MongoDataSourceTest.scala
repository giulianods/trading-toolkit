package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.dataservice.PolygonDataSourceTest.test
import zio.{Scope, ZIO}
import zio.test.{Spec, ZIOSpecDefault}
import zio.test.*

import java.time.{LocalDate, ZoneId}
import java.util.TimeZone

object MongoDataSourceTest extends ZIOSpecDefault {
  val zoneId = ZoneId.of("America/New_York")
  val timezone = TimeZone.getTimeZone(zoneId.getId)
  val firstTest: Spec[MongoDataRepo, Throwable] = test("some test")(
    for {
      repo <- ZIO.service[MongoDataRepo]
      _ <- repo.saveStockUniverse(StockUniverse(
        _id = "us-common-stocks-polygon3",
        name = "US Common Stocks",
        dataSource = "polygon",
        lastUpdateQuotes = LocalDate.now(zoneId),
        timeZone = zoneId.getId,
        symbols = Seq.empty
      ))
    } yield assertTrue(true)
  )

  val getTest: Spec[MongoDataRepo, Throwable] = test("get test")(
    for {
      repo <- ZIO.service[MongoDataRepo]
      su <- repo.getStockUniverse
      _ <- ZIO.logInfo(s"Found stock universe $su")
    } yield assertTrue(true)
  )


  override def spec: Spec[TestEnvironment, Any] =
    suite("Mongo tests")(
      firstTest
    ).provideLayer(MongoDataRepo.build("mongodb://localhost:27017", "polygon"))
}
