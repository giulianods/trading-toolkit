package io.greensheep.trading.service

import io.greensheep.providers.polygon.PolygonAsyncApi
import io.greensheep.providers.polygon.datatypes.Aggregate
import io.greensheep.trading.dataservice.{MongoDbDataRepo, PolygonDataSource}
import zio.ZIOAppDefault
import zio.*
import zio.interop.catz.*
import zio.http.{Client, URL}
import zio.http.Header.Authorization
import zio.stream.{Stream, ZStream}

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.jdk.CollectionConverters.*
import scala.concurrent.{ExecutionContext, Future}


object GetDailySnapshot extends ZIOAppDefault {
  implicit val ec: ExecutionContext = ExecutionContext.global

  private def getDatesBetween(startDate: LocalDate, endDate: LocalDate): Stream[Throwable, LocalDate] = {
      ZStream.fromJavaStream(startDate.datesUntil(endDate))
  }

  private def datesBetween(startDate: LocalDate, endDate: LocalDate) = startDate.datesUntil(endDate).iterator().asScala.toSeq

  val datesToRetrieve = datesBetween(LocalDate.of(2023,1, 3), LocalDate.now().minusDays(1))

  def flattenChunk(chunk: Chunk[ZIO[Any, Throwable, Map[String, Aggregate]]]) = {
    //TODO
  }


  val getAggregatesRangeApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]
      api <- PolygonAsyncApi.build()
      responseAggregates = datesToRetrieve
        .map(c => api.getGroupedDaily(c))
      all <- ZIO.collectAll(responseAggregates)
      result = all.map(_.map(allAggs => {
        val start = java.lang.System.currentTimeMillis()
        allAggs.map(repo.saveAggregate)
        println(s"Saved in ${(java.lang.System.currentTimeMillis() - start)} millis")
      }).recover(th => th.printStackTrace()))
      allF = Future.sequence(result)
      _ <- ZIO.fromFuture(ec => allF)
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  val getAggregatesApp: ZIO[MongoDbDataRepo with PolygonDataSource with Client, Throwable, Unit] = {
    val time = java.lang.System.currentTimeMillis()
    for {
      source <- ZIO.service[PolygonDataSource]
      repo <- ZIO.service[MongoDbDataRepo]

      allData <- source.getSnapshot("2024-01-09")
      _ <- ZIO.foreachParDiscard(allData) {
        case (ticker, agg) =>
          ZIO.succeed(repo.saveAggregates(ticker, Seq(agg)))
      }
      _ = println(s"Finished in ${(java.lang.System.currentTimeMillis() - time) / 1000} seconds")
    } yield ()
  }

  override def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Any] = {
    val apiKey = java.lang.System.getenv("POLYGON_API_KEY")

    val clientLayer = Client.default.map(_.update(
      client =>
        client.addHeader(Authorization.Bearer(apiKey))
          .url(URL.decode("https://api.polygon.io").toOption.get)
    ))

    getAggregatesRangeApp.provide(MongoDbDataRepo.layer ++ PolygonDataSource.layer ++ clientLayer)
  }
}
