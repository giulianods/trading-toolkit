package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.driver.filters.Filter
import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.github.zeal18.zio.mongodb.driver.filters
import zio.ZLayer
import zio.*

object MongoDataRepo {


  val client: ZLayer[Any, Throwable, MongoClient] = MongoClient.live("mongodb://localhost:27017")

  val stockUniverseDatabase: ZLayer[Any, Throwable, MongoDatabase] =
    client >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("database-name")))





  def getStockUniverse: ZIO[MongoDatabase, Throwable, Unit] = {
    for{
      db <- ZIO.service[MongoDatabase]
      _ <- db.createCollection("stock-universe")
      coll = db.getCollection[StockUniverse]("stock-universe")
     // _ <- coll.find(Filter.Eq("id", "us-stocks", DeriveBsonCodec.gen[StockUniverse]))


    } yield ()

  }


}