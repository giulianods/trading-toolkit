package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.driver.model.{ReplaceOptions, UpdateOptions}
import io.github.zeal18.zio.mongodb.driver.sorts.Sort
import io.github.zeal18.zio.mongodb.driver.updates.{Update, addEachToSet}
import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoClientSettings, MongoDatabase, filters, updates}
import io.greensheep.trading.core.types.{DailyQuoteData, Quote, ScreenStats, Stock, StockDescription, Watchlist}
import zio.*
import zio.stream.ZStream
import zio.interop.reactivestreams.*
import io.github.zeal18.zio.mongodb.bson.codecs.Codec.*
import io.github.zeal18.zio.mongodb.bson.codecs.{CaseClassCodec, Codec, Decoder, Encoder}
import org.bson.BsonWriter
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistries





object MongoDataRepo {




  implicit val screenStatsCodec: Codec[Map[String,String]] = Codec(
    (writer: BsonWriter, value: Map[String, String], encoderContext) => {
      writer.writeStartDocument()
      value.foreach { case (k, v) =>
        writer.writeString(k, v)
      }
      writer.writeEndDocument()
    },
    (reader, decoderContext) => {
      reader.readStartDocument()
      val map = scala.collection.mutable.Map[String, String]()
      while (reader.readBsonType() != org.bson.BsonType.END_OF_DOCUMENT) {
        val key = reader.readName()
        val value = reader.readString()
        map += (key -> value)
      }
      reader.readEndDocument()
      map.toMap
    }
  )

  def build(url: String, dbName: String): ZLayer[Any, Throwable, MongoDataRepo] = ZLayer.succeed {
    val mongoLayer = MongoClient.live(url) >>> ZLayer.fromZIO(ZIO.serviceWith[MongoClient](_.getDatabase(dbName)))
    new MongoDataRepo() {
      override def saveStockUniverse(stockUniverse: StockUniverse): ZIO[Any, Throwable, Unit] =
        MongoDataRepo.saveStockUniverse(stockUniverse).provideLayer(mongoLayer)

      override def getStockUniverse: ZIO[Any, Throwable, StockUniverse] =
        MongoDataRepo.getStockUniverse.provideLayer(mongoLayer)

      override def updateDailyQuotes(ticker: String, quotes: Seq[Quote]): ZIO[Any, Throwable, Unit] =
        MongoDataRepo.updateDailyQuotes(ticker, quotes).provideLayer(mongoLayer)

      override def getPriceData(ticker: String): ZIO[Any, Throwable, DailyQuoteData] =
        MongoDataRepo.getPriceData(ticker).provideLayer(mongoLayer)

      override def hasStockUniverse: ZIO[Any, Throwable, Boolean] =
        MongoDataRepo.hasStockUniverse.provideLayer(mongoLayer)

      override def saveWatchlist(wl: Watchlist): ZIO[Any, Throwable, Unit] =
        MongoDataRepo.saveWatchlist(wl).provideLayer(mongoLayer)

      override def getWatchlistLatest(name: String): ZIO[Any, Throwable, Watchlist] =
        MongoDataRepo.getWatchlistLatest(name).provideLayer(mongoLayer)
    }
  }

  def saveStockUniverse(stockUniverse: StockUniverse): ZIO[MongoDatabase, Throwable, Unit] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StockUniverse]("stock-universe")
      _ <- coll.replaceOne(filters.eq("_id", stockUniverse._id),
        stockUniverse,
        ReplaceOptions(upsert = true)).map(_.wasAcknowledged())

    } yield ()
  }

  def hasStockUniverse: ZIO[MongoDatabase, Throwable, Boolean] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StockUniverse]("stock-universe")
      count <- coll.countDocuments().map(_.toInt)
      _ <- ZIO.logInfo(s"Stock universe count: $count")
    } yield count > 0
  }

  def getStockUniverse: ZIO[MongoDatabase, Throwable, StockUniverse] = {
    for {
      db <- ZIO.service[MongoDatabase]
      _ <- db.createCollection("stock-universe")
      coll = db.getCollection[StockUniverse]("stock-universe")
      su <- coll.find(filters.eq("_id", "stock-universe")).runHead
      _ <- ZIO.cond(su.isDefined, (), new NoSuchElementException("No stock universe found"))
    } yield su.get
  }

  def updateDailyQuotes(ticker: String, quotes: Seq[Quote]): ZIO[MongoDatabase, Throwable, Unit] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[Quote]("daily-quotes")
      _ <- coll.updateOne(filters.eq("_id", ticker),
          addEachToSet("quotes", quotes), UpdateOptions(upsert = true)).map(_.wasAcknowledged())

    } yield ()
  }

  def getPriceData(ticker: String): ZIO[MongoDatabase, Throwable, DailyQuoteData] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[DailyQuoteData]("daily-quotes")
      quotesOpt <- coll.find(filters.eq("_id", ticker)).runHead
      quotes <- ZIO.fromOption(quotesOpt).orElseFail(new NoSuchElementException(s"No quotes found for ticker $ticker"))
    } yield quotes
  }

  def saveWatchlist(wl: Watchlist): ZIO[MongoDatabase, Throwable, Unit] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[Watchlist]("watchlist")
      _ <- coll.replaceOne(filters.eq("_id", wl.uuid),
        wl,
        ReplaceOptions(upsert = true)).map(_.wasAcknowledged())
    } yield ()
  }

  def getWatchlistLatest(name: String) : ZIO[MongoDatabase, Throwable, Watchlist] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[Watchlist]("watchlist")
      watchlistOpt <- coll.find(filters.eq("screenName", name)).sort(Sort.Desc("day")).runHead
      watchlist <- ZIO.fromOption(watchlistOpt).orElseFail(new NoSuchElementException(s"No watchlist found for name $name"))
    } yield watchlist
  }

  def saveStockDescription(stock: StockDescription): ZIO[MongoDatabase, Throwable, StockDescription] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StockDescription]("stocks-description")
      _ <- coll.replaceOne(filters.eq("_id", stock.symbol),
        stock,
        ReplaceOptions(upsert = true)).map(_.wasAcknowledged())
    } yield stock
  }

  def getStockDescription(symbol: String): ZIO[MongoDatabase, Throwable, StockDescription] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StockDescription]("stocks-description")
      stockOpt <- coll.find(filters.eq("_id", symbol)).runHead
      stock <- ZIO.fromOption(stockOpt).orElseFail(new NoSuchElementException(s"No stock found for symbol $symbol"))
    } yield stock
  }

  def getAllStockDescriptions: ZIO[MongoDatabase, Throwable, Seq[StockDescription]] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StockDescription]("stocks-description")
      pub = coll.find().sort(Sort.Asc("_id")).run
      stocks <- pub.toZIOStream().runCollect.map(_.toSeq)
    } yield stocks
  }

}

trait MongoDataRepo {

  def saveStockUniverse(stockUniverse: StockUniverse): ZIO[Any, Throwable, Unit]

  def getStockUniverse: ZIO[Any, Throwable, StockUniverse]

  def hasStockUniverse: ZIO[Any, Throwable, Boolean]

  def updateDailyQuotes(ticker: String, quotes: Seq[Quote]): ZIO[Any, Throwable, Unit]

  def getPriceData(ticker: String): ZIO[Any, Throwable, DailyQuoteData]

  def saveWatchlist(wl: Watchlist): ZIO[Any, Throwable, Unit]

  def getWatchlistLatest(name: String): ZIO[Any, Throwable, Watchlist]
}