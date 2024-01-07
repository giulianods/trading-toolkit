package io.greensheep.trading.dataservice

import com.mongodb.client.MongoClients
import com.mongodb.client.model.{CreateCollectionOptions, TimeSeriesGranularity, TimeSeriesOptions}
import com.mongodb.client.result.InsertOneResult
import io.greensheep.providers.polygon.datatypes.Aggregate
import io.greensheep.providers.polygon.{PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.TickersRequest
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.{Quote, Stock}
import org.bson.Document
import zio.http.Client
import zio.stream.ZStream
import zio.{IO, Task, ULayer, ZLayer}

import java.time.LocalDate
import scala.jdk.CollectionConverters.MapHasAsJava
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.jdk.CollectionConverters.SeqHasAsJava
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

  def createAggregatesCollection(symbol: String): Unit = {
    val database = mongoClient.getDatabase(databaseName + "-data")
    val options = new CreateCollectionOptions()
      .timeSeriesOptions(new TimeSeriesOptions("t")
        .granularity(TimeSeriesGranularity.HOURS))
    database.createCollection(symbol, options)
  }

  def getAggregatesCollection(symbol: String) = {
    val database = mongoClient.getDatabase(databaseName + "-data")
    var collection = database.getCollection(symbol)
    if(collection == null){
      createAggregatesCollection(symbol)
      collection = database.getCollection(symbol)
    }
    collection
  }

  def saveAggregates(symbol: String, aggregates: Seq[Aggregate]) = {
    val collection = getAggregatesCollection(symbol)
    def aggregateToDocument(agg: Aggregate) = {
      new Document(
        Map(
          "_id" -> agg.t,
          "o" -> agg.o,
          "h" -> agg.o,
          "l" -> agg.o,
          "c" -> agg.o,
          "t" -> agg.t,
          "n" -> agg.n,
      ).asJava)
    }
    println(s"Saving aggregates for $symbol")
    collection.insertMany(aggregates.map(aggregateToDocument).asJava)
  }

  def saveTicker(stock: Stock): InsertOneResult = {
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

  def getTickers: ZStream[Any, Throwable, Stock] = {
    def documentToStock(d: Document): Stock = {
      Stock(
        d.get("symbol", classOf[String]),
        d.get("name", classOf[String]),
        LocalDate.now(),
        ""
      )
    }

    val collection = getCollection("tickers").get
    val stockIterator = collection.find().map(documentToStock).iterator().asScala
    ZStream.fromIterator(stockIterator)
  }

  override def saveQuotes(symbol: String, quotes: Seq[Quote]): Task[Unit] = ???

  override def getSymbols: Task[Seq[String]] = ???

  override def getStock(symbol: String): IO[Throwable, Stock] = ???

  override def getStocks(): Map[String, Stock] = ???

  override def getQuotes(symbol: String): Task[Seq[Quote]] = ???
}
