package io.greensheep.trading.dataservice

import com.mongodb.BasicDBObject
import com.mongodb.client.{MongoClients, MongoCollection, MongoDatabase}
import com.mongodb.client.model.{CreateCollectionOptions, Filters, FindOneAndUpdateOptions, InsertOneOptions, TimeSeriesGranularity, TimeSeriesOptions, UpdateOneModel, UpdateOptions, Updates}
import com.mongodb.client.result.{InsertManyResult, InsertOneResult, UpdateResult}
import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.providers.polygon.datatypes.{Aggregate, TickerDetails}
import io.greensheep.providers.polygon.{PolygonSettings, ReferenceClient}
import io.greensheep.providers.polygon.requests.TickersRequest
import io.greensheep.trading.core.services.{DataRepo, DataSource}
import io.greensheep.trading.core.types.{AnnualFinancialData, DailyAggregate, QuarterlyFinancialData, Quote, ScreenStats, Stock, Watchlist}
import org.bson.Document
import zio.http.Client
import zio.stream.ZStream
import zio.{Chunk, IO, Task, ULayer, ZIO, ZLayer}
import zio.json.{DeriveJsonEncoder, EncoderOps, JsonEncoder}
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
import scala.collection.immutable.ListMap
object MongoDbDataRepo{

  val layer: ULayer[MongoDbDataRepo] = ZLayer.succeed(build())

  def build(): MongoDbDataRepo = {
    new MongoDbDataRepo("mongodb://localhost:27017", "polygon")
  }


  val mongoClient = MongoClient.live("mongodb://localhost:27017")

  val database = mongoClient >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase("database-name")))


}

class MongoDbDataRepo(url: String, databaseName: String) extends DataRepo{

  private val mongoClient = MongoClients.create(url)
  val database: MongoDatabase = mongoClient.getDatabase(databaseName)
  val quotesCollection: MongoCollection[Document] = getCollection("daily-quotes", databaseName).get
  val financialsCollection: MongoCollection[Document] = getCollection("financials", databaseName).get



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

  private def aggregateToDocument(agg: Aggregate) =
    new Document(
      Map(
        "_id" -> agg.t,
        "o" -> agg.o,
        "h" -> agg.h,
        "l" -> agg.l,
        "c" -> agg.c,
        "t" -> agg.t,
        "v" -> agg.v,
        "day" -> Instant.ofEpochMilli(agg.t)
          .atZone(ZoneId.of("America/New_York"))
          .format(DateTimeFormatter.ISO_DATE),
        "n" -> agg.numberOfShares,
        "vw" -> agg.wwap
      ).asJava)

  def saveAggregate(agg: Aggregate): InsertOneResult = {
    val collection = getAggregatesCollection(agg.T.getOrElse("?"))
    val doc = aggregateToDocument(agg)
    collection.insertOne(doc)
  }

  def saveAggregates(symbol: String, aggregates: Seq[Aggregate]): InsertManyResult = {
    val collection = getAggregatesCollection(symbol)
    val filter = Filters.eq("_id", symbol)
    Option(quotesCollection.find(filter).first())
    collection.insertMany(aggregates.map(aggregateToDocument).asJava)

  }

  def aggregateToMap(agg: Aggregate) = {
    ListMap(
    "o" -> agg.o,
      "h" -> agg.h,
      "l" -> agg.l,
      "c" -> agg.c,
      "t" -> agg.t,
      "v" -> agg.v,
      "day" -> Instant.ofEpochMilli(agg.t)
        .atZone(ZoneId.of("America/New_York"))
        .format(DateTimeFormatter.ISO_DATE),
      "n" -> agg.numberOfShares,
      "vw" -> agg.wwap
    ).asJava
  }

  def updateFinancials(symbol: String, annual: Seq[AnnualFinancialData], quarterly: Seq[QuarterlyFinancialData]): Unit = {  
    def annualToDocument(annual: AnnualFinancialData) = {
      new Document(
        Map(
          "year" -> annual.year,
          "revenuePerShare" -> annual.revenuePerShare,
          "basicEarningsPerShare" -> annual.basicEarningsPerShare,
          "operatingMargin" -> annual.operatingMargin,
          "netIncomeMargin" -> annual.netIncomeMargin
        ).asJava)
    }

    def quarterlyToDocument(quarterly: QuarterlyFinancialData) = {
      new Document(
        Map(
          "year" -> quarterly.year,
          "quarter" -> quarterly.quarter,
          "revenuePerShare" -> quarterly.revenuePerShare,
          "basicEarningsPerShare" -> quarterly.basicEarningsPerShare,
          "operatingMargin" -> quarterly.operatingMargin,
          "netIncomeMargin" -> quarterly.netIncomeMargin
        ).asJava)
    }

    val filter = Filters.eq("_id", symbol)
    val symbolUpdate = Updates.set("symbol", symbol)
    Option(financialsCollection.find(filter).first()) match {
      case Some(document) =>
        val annuals = document.getList("annual", classOf[Document], List.empty[Document].asJava).asScala
        val quarterlys = document.getList("quarterly", classOf[Document], List.empty[Document].asJava).asScala
        val annualsMap = annuals.map(doc => doc.get("year").toString.toInt -> doc).toMap
        val quarterlysMap = quarterlys.map(doc => doc.get("year").toString.toInt -> doc).toMap
        val newAnnual = annual.filter(a => !annualsMap.contains(a.year))
        val newQuarterly = quarterly.filter(q => !quarterlysMap.contains(q.year * 10 + q.quarter))
        val updates1 = Updates.set("annual", newAnnual.map(annualToDocument).asJava)
        val updates2 = Updates.set("quarterly", newQuarterly.map(quarterlyToDocument).asJava)        
        val updates = Updates.combine(symbolUpdate, updates1, updates2)
        financialsCollection.updateOne(filter, updates1, new UpdateOptions().upsert(true))
      case None =>
        val updates1 = Updates.set("annual", annual.map(annualToDocument).asJava)
        val updates2 = Updates.set("quarterly", quarterly.map(quarterlyToDocument).asJava)
        val updates = Updates.combine(symbolUpdate, updates1, updates2)
        val options = new UpdateOptions().upsert(true)
        financialsCollection.updateOne(filter,updates, options)
    }
  }


  def updateAggregates(symbol: String, aggregates: Seq[Aggregate], overwrite: Boolean = false): Any = {
    val filter = Filters.eq("_id", symbol)
    Option(quotesCollection.find(filter).first()) match
      case Some(document) =>
        if(overwrite) {
          val updates = Updates.set("quotes", aggregates.map(aggregateToMap).asJava)
          val options = new UpdateOptions().upsert(true)
          quotesCollection.updateOne(filter, updates, options)
        }
        else{
          val quotes = document.getList("quotes", classOf[Document], List.empty[Document].asJava).asScala
          val existing = quotes.map(doc => doc.get("t").toString.toLong).toSet
          val newQuotes = aggregates.filter(agg => !existing.contains(agg.t))
          if (newQuotes.nonEmpty) {
            val updates = Updates.pushEach("quotes", newQuotes.map(aggregateToMap).asJava)
            val options = new UpdateOptions().upsert(true)
            quotesCollection.updateOne(filter, updates, options)
          }
          else {
            //don't add the same quotes
          }
        }

      case None =>
        val updates = Updates.pushEach("quotes", aggregates.map(aggregateToMap).asJava)
        val options = new UpdateOptions().upsert(true)
        quotesCollection.updateOne(filter, updates, options)
  }

  private def convertUnixTimestampToLocalDate(unixTimestamp: Long): LocalDate = {
    val instant = Instant.ofEpochSecond(unixTimestamp)
    instant.atZone(ZoneId.of("America/New_York")).toLocalDate
  }


  override def getQuotes(symbol: String): Task[Seq[Quote]] = {
    for {
      aggregates <- getAggregates2(symbol)
      quotes = aggregates.map { agg =>
        Quote(
          day = agg.t,
          o = agg.o,
          h = agg.h,
          l = agg.l,
          c = agg.c,
          v = agg.v.toInt
        )
      }

    } yield quotes
  }

  def getAggregates2(symbol: String): ZIO[Any, Throwable, Chunk[DailyAggregate]] = {
    val tryIterable = Try{
      val iterable = quotesCollection.find(Filters.eq("_id", symbol)).asScala
      iterable.headOption match {
        case Some(doc) =>
          val quotes = doc.get("quotes").asInstanceOf[java.lang.Iterable[Document]].asScala
          val processed = quotes.map { doc =>
            DailyAggregate(
              o = doc.get("o").toString.toDouble,
              h = doc.get("h").toString.toDouble,
              l = doc.get("l").toString.toDouble,
              c = doc.get("c").toString.toDouble,
              v = Option(doc.get("v")).map(_.toString.toDouble).getOrElse(0.0),
              t = convertUnixTimestampToLocalDate(doc.get("t").toString.toLong),
            )
          }.toSeq
          Chunk.fromIterable(processed)
        case None => Chunk.empty
      }
    }
    ZIO.fromTry(tryIterable)
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

  def getTickerDetails: ZIO[Any, Throwable, Seq[Stock]] = {
    val collection = getCollection("ticker-details").get
    val stockIterator = collection.find().asScala.map { doc =>
      Stock(
        symbol = doc.get("_id").asInstanceOf[String],
        companyName = doc.get("name").asInstanceOf[String],
        ipoDate = LocalDate.parse(Option(doc.get("list_date").asInstanceOf[String]).getOrElse("1970-01-01")),
        primaryExchange = doc.get("primary_exchange").asInstanceOf[String],
        description = doc.get("description").asInstanceOf[String],
        totalEmployees = Option(doc.get("total_employees").asInstanceOf[Decimal128]).map(_.intValue()).getOrElse(0),
        sicCode = doc.get("sic_code").asInstanceOf[String],
        sicDescription = doc.get("sic_description").asInstanceOf[String],
        marketCap = Option(doc.get("market_cap").asInstanceOf[Decimal128]).map(_.longValue()).getOrElse(0L),
        weightedSharesOutstanding = Option(doc.get("weighted_shares_outstanding").asInstanceOf[Decimal128]).map(_.longValue()).getOrElse(0L),
      )
    }
    ZIO.succeed(stockIterator.toSeq)
  }

  def saveTicker(stock: Stock): UpdateResult = {
    val collection = getCollection("tickers").get


    val docMap = Map(
      "symbol" -> stock.symbol,
      "companyName" -> stock.companyName,
      "ipoDate" -> stock.ipoDate.toString
    )

    val doc = new Document((docMap + ("_id" -> stock.symbol)).asJava)
    val filter = Filters.eq("_id", stock.symbol)
    val options = new UpdateOptions().upsert(true)

    collection.updateOne(filter, new Document("$set", doc), options)
  }

  implicit val Encoder: JsonEncoder[TickerDetails] = DeriveJsonEncoder.gen[TickerDetails]

  def saveTicker(stock: TickerDetails): UpdateResult = {
    println(s"Saving ticker ${stock.ticker}")
    val collection = getCollection("ticker-details").get
    val docMap = stock.toJsonAST match
      case Left(value) =>
        throw new RuntimeException(value)
      case Right(value) =>
        value.toMap

    val doc = new Document((docMap + ("_id" -> stock.ticker)).asJava)
    val filter = Filters.eq("_id", stock.ticker)
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
      day = document.get("day").asInstanceOf[LocalDate],
      screenName = document.get("screenName").asInstanceOf[String],
      tickers = document.get("tickers").asInstanceOf[java.lang.Iterable[String]].asScala.toSeq,
      stats = document.get("stats").asInstanceOf[java.lang.Iterable[Document]].asScala.map { doc =>
        ScreenStats(
          ticker = doc.get("ticker").asInstanceOf[String],
          stats = doc.get("stats").asInstanceOf[Document].asInstanceOf[Map[String, String]]
        )
      }.toSeq
    )
  }





 

  def saveFinancialsAnnually(ticker: String, data: Chunk[AnnualFinancialData]) = {
    val collection = getCollection("annual-financials", "polygon").get

    def toDocument(agg: AnnualFinancialData) = {
      val dataMap = agg.toJsonAST match
        case Left(value) =>
          println(value)
          Map.empty[String, Object]
        case Right(value) =>
          value.toMap

      new Document(
        (dataMap + ("_id" -> (ticker + "-" + agg.year.toString))).asJava)
    }

    if (data.nonEmpty) {
      val opt = new UpdateOptions().upsert(true)
      val actions = data map { d =>
        val doc = toDocument(d)
        val filter = new BasicDBObject()
        filter.append("_id", doc.get("_id"))
        val action = new BasicDBObject("$set", doc)
        new UpdateOneModel[Document](filter, action, opt)
      }
      collection.bulkWrite(actions.asJava)
    }
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
        d.get("_id", classOf[String]),
        d.get("companyName", classOf[String]),
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


}
