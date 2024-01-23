package io.greensheep.trading.dataservice

import com.mongodb.client.MongoClients
import com.mongodb.client.model.{CreateCollectionOptions, TimeSeriesGranularity, TimeSeriesOptions}
import com.mongodb.client.result.{InsertManyResult, InsertOneResult}
import io.greensheep.providers.polygon.datatypes.Aggregate
import io.greensheep.providers.polygon.{PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.TickersRequest
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.{DailyAggregate, QuarterlyFinancialData, Quote, Stock}
import org.bson.Document
import zio.http.Client
import zio.stream.ZStream
import zio.{Chunk, IO, Task, ULayer, ZIO, ZLayer}
import zio.json.EncoderOps
import zio.json.ast.Json

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, ZoneId}
import scala.collection.mutable
import scala.jdk.CollectionConverters.MapHasAsJava
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.jdk.CollectionConverters.IterableHasAsScala
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.util.Try
import io.greensheep.trading.core.utils.JsonUtils._
object MongoDbDataRepo{

  val layer: ULayer[MongoDbDataRepo] = ZLayer.succeed(build())

  def build(): MongoDbDataRepo = {
    new MongoDbDataRepo("mongodb://localhost:27017", "polygon")
  }
}

class MongoDbDataRepo(url: String, databaseName: String) extends DataRepo{

  private val mongoClient = MongoClients.create(url)

  private def getCollection(name: String): Try[com.mongodb.client.MongoCollection[Document]] = Try {
    val database = mongoClient.getDatabase(databaseName)
    var collection = database.getCollection(name)
    if(collection == null) {
      database.createCollection(name)
      collection = database.getCollection(name)
    }
    collection
  }

  private def createAggregatesCollection(symbol: String): Unit = {
    val database = mongoClient.getDatabase(databaseName + "-data")
    val options = new CreateCollectionOptions()
      .timeSeriesOptions(new TimeSeriesOptions("t")
        .granularity(TimeSeriesGranularity.HOURS))
    database.createCollection(symbol, options)
  }

  private def getAggregatesCollection(symbol: String) = {
    val database = mongoClient.getDatabase(databaseName + "-data")
    var collection = database.getCollection(symbol)
    if(collection == null){
      createAggregatesCollection(symbol)
      collection = database.getCollection(symbol)
    }
    collection
  }

  private def getFinancialsCollection(symbol: String) = {
    val database = mongoClient.getDatabase(databaseName + "-financials")
    var collection = database.getCollection(symbol)
    if (collection == null) {
      createAggregatesCollection(symbol)
      collection = database.getCollection(symbol)
    }
    collection
  }

  def saveAggregates(symbol: String, aggregates: Seq[Aggregate]): InsertManyResult = {
    val collection = getAggregatesCollection(symbol)
    def aggregateToDocument(agg: Aggregate) = {
      new Document(
        Map(
          "_id" -> agg.t,
          "o" -> agg.o,
          "h" -> agg.h,
          "l" -> agg.l,
          "c" -> agg.c,
          "t" -> agg.t,
          "v" -> agg.v,
          "day" -> Instant.ofEpochMilli(agg.t).atZone(ZoneId.of("America/New_York"))
            .format(DateTimeFormatter.ISO_DATE),
          "n" -> agg.numberOfShares,
          "vw" -> agg.wwap
      ).asJava)
    }
    collection.insertMany(aggregates.map(aggregateToDocument).asJava)
  }

  def convertUnixTimestampToLocalDate(unixTimestamp: Long): LocalDate = {
    val instant = Instant.ofEpochSecond(unixTimestamp)
    instant.atZone(ZoneId.of("America/New_York")).toLocalDate
  }

  def getAggregates(symbol: String): ZStream[Any, Throwable, DailyAggregate] = {
    val tryIterable = Try{
      val collection = getAggregatesCollection(symbol)
      val iterable = collection.find().asScala
      def documentToAggregate(doc: Document) = {
        DailyAggregate(
          o = doc.get("o").toString.toDouble,
          h = doc.get("h").toString.toDouble,
          l = doc.get("l").toString.toDouble,
          c = doc.get("c").toString.toDouble,
          v = Option(doc.get("v")).map(_.toString.toDouble).getOrElse(0.0),
          t = convertUnixTimestampToLocalDate(doc.get("t").toString.toLong),

        )
      }
      iterable.map(documentToAggregate)
    }
    ZStream.fromZIO(ZIO.fromTry(tryIterable)).flatMap(ZStream.fromIterable)
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

  def saveFinancials(ticker: String, data: Chunk[QuarterlyFinancialData]) = {
    val collection = getFinancialsCollection(ticker)

    def toDocument(agg: QuarterlyFinancialData) = {
      val dataMap = agg.toJsonAST match
        case Left(value) =>
          println(value)
          Map.empty[String, Object]
        case Right(value) =>
          value.toMap

      new Document(
        (dataMap + ("_id" -> (agg.year.toString + "-" + agg.quarter))).asJava)
    }

    if(data.nonEmpty){
      val results = data.map(toDocument)
      val unduplicate = results.map(d => d.get("_id") -> d).toMap.values
      collection.insertMany(unduplicate.toSeq.asJava)
    }

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
