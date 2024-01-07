package io.greensheep.trading.dataservice

import com.mongodb.client.MongoClients
import com.mongodb.client.model.{CreateCollectionOptions, TimeSeriesGranularity, TimeSeriesOptions}
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.{Quote, Stock}
import org.bson.Document
import zio.{IO, Task, ULayer, ZLayer}

import scala.jdk.CollectionConverters.MapHasAsJava
import scala.util.Try
object MongoDbDataRepo{

  val layer: ULayer[MongoDbDataRepo] = ZLayer.succeed(build())

  def build() = {
    new MongoDbDataRepo("mongodb://localhost:27017", "polygon")
  }
}

class MongoDbDataRepo(url: String, databaseName: String) extends DataRepo{

  val mongoClient = MongoClients.create("mongodb://localhost:27017")



  def getCollection(name: String): Try[com.mongodb.client.MongoCollection[Document]] = Try {
    val database = mongoClient.getDatabase(databaseName)
    var collection = database.getCollection(name)
    if(collection == null) {
      database.createCollection(name)
      collection = database.getCollection(name)
    }
    collection
  }

  def createAggregatesCollection(): Unit = {
    val database = mongoClient.getDatabase(databaseName)
    val options = new CreateCollectionOptions()
      .timeSeriesOptions(new TimeSeriesOptions("timestamp")
        .granularity(TimeSeriesGranularity.HOURS))
    database.createCollection("daily-aggregates", options)
  }


  def saveTicker(stock: Stock) = {
    println(s"Saving ${stock.symbol}")
    val collection = getCollection("tickers").get
    val doc = new Document(
      Map(
        "_id" -> stock.symbol,
        "symbol" -> stock.symbol,
        "name" -> stock.companyName,
        "ipoDate" -> stock.ipoDate).asJava
    )
    collection.insertOne(doc)
  }

  override def saveQuotes(symbol: String, quotes: Seq[Quote]): Task[Unit] = ???

  override def getSymbols: Task[Seq[String]] = ???

  override def getStock(symbol: String): IO[Throwable, Stock] = ???

  override def getStocks(): Map[String, Stock] = ???

  override def getQuotes(symbol: String): Task[Seq[Quote]] = ???
}
