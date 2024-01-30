package io.greensheep.trading.dataservice

import com.mongodb.client.MongoClients
import com.mongodb.client.model.{CreateCollectionOptions, Filters, InsertOneOptions, TimeSeriesGranularity, TimeSeriesOptions, UpdateOptions}
import com.mongodb.client.result.{InsertManyResult, InsertOneResult, UpdateResult}
import io.greensheep.providers.polygon.datatypes.Aggregate
import io.greensheep.providers.polygon.{PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.TickersRequest
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.{DailyAggregate, QuarterlyFinancialData, Quote, Stock, Watchlist}
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
import io.greensheep.trading.core.utils.JsonUtils.*
import org.bson.types.Decimal128

import java.util
object MongoDbDataRepo{

  val layer: ULayer[MongoDbDataRepo] = ZLayer.succeed(build())

  def build(): MongoDbDataRepo = {
    new MongoDbDataRepo("mongodb://localhost:27017", "polygon")
  }
}

class MongoDbDataRepo(url: String, databaseName: String) extends DataRepo{

  private val mongoClient = MongoClients.create(url)

  private def getCollection(name: String, dbName: String = databaseName): Try[com.mongodb.client.MongoCollection[Document]] = Try {
    val database = mongoClient.getDatabase(dbName)
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

  def saveTicker(stock: Stock): UpdateResult = {
    println(s"Saving ${stock.symbol}")
    val collection = getCollection("tickers").get
    val docMap = stock.toJsonAST match
      case Left(value) =>
        throw new RuntimeException(value)
      case Right(value) =>
        value.toMap

    val filteredMap = docMap - ("dailyAggregates") // TODO investigate
    val doc = new Document((filteredMap + ("_id" -> stock.symbol)).asJava)
    val filter = Filters.eq("_id", stock.symbol)
    val options = new UpdateOptions().upsert(true)

    collection.updateOne(filter, new Document("$set", doc), options)
  }

  def saveWatchlist(w: Watchlist): InsertOneResult = {
    val wlCollection = getCollection("watchlist", "greensheep-trading")

    def toDocument(agg: Watchlist) = {
      val javaMap = new util.LinkedHashMap[String, Object]()
      javaMap.put("_id", agg.uuid)
      javaMap.put("day", agg.day)
      javaMap.put("screenName", agg.screenName)
      javaMap.put("dataSource", agg.dataSource)
      javaMap.put("tickers", agg.tickers.asJava)

      new Document(javaMap)
    }

    val doc = toDocument(w)
    wlCollection.get.insertOne(doc)
  }

  def getWatchlist(day: String, screen: String): Watchlist = {
    val uuid = s"$day-$screen-polygon"
    val wlCollection = getCollection("watchlist", "greensheep-trading").get
    val document = wlCollection.find(com.mongodb.client.model.Filters.eq("_id", uuid)).first()
    Watchlist(
      day = document.get("day").asInstanceOf[String],
      screenName = document.get("screenName").asInstanceOf[String],
      dataSource = document.get("dataSource").asInstanceOf[String],
      tickers = document.get("tickers").asInstanceOf[java.lang.Iterable[String]].asScala.toSeq
    )
  }


  def getFinancialsQuarterly(ticker: String): Seq[QuarterlyFinancialData] = {
    val collection = getFinancialsCollection(ticker)
    val entries = collection.find()
    val qdata = entries.asScala map { doc =>
      QuarterlyFinancialData(
        quarter = doc.get("quarter").asInstanceOf[String],
        year = doc.get("year").asInstanceOf[Decimal128].intValue(),
        revenues = doc.get("revenues").asInstanceOf[Decimal128].doubleValue(),
        basicEarningsPerShare = doc.get("basicEarningsPerShare").asInstanceOf[Decimal128].doubleValue(),
        dilutedEarningsPerShare = doc.get("dilutedEarningsPerShare").asInstanceOf[Decimal128].doubleValue(),
        netCashFlow = doc.get("netCashFlow").asInstanceOf[Decimal128].doubleValue(),
        netCashFlowFromOperatingActivities = doc.get("netCashFlowFromOperatingActivities").asInstanceOf[Decimal128].doubleValue(),
        netCashFlowFromInvestingActivities = doc.get("netCashFlowFromInvestingActivities").asInstanceOf[Decimal128].doubleValue(),
        netCashFlowFromFinancingActivities = doc.get("netCashFlowFromFinancingActivities").asInstanceOf[Decimal128].doubleValue()
      )
    }
    qdata.toSeq.sortBy(_.uuid)
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

  override def getSymbols: ZIO[Any, Throwable, Seq[String]] = {
    val result = getTickers.map(_.symbol).runCollect
    result
  }

  override def getStock(symbol: String): IO[Throwable, Stock] = ???

  override def getStocks(): Task[Map[String, Stock]] = ???

  override def getQuotes(symbol: String): Task[Seq[Quote]] = ???
}
