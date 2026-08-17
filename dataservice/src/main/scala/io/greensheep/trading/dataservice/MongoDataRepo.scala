package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.bson.annotations.BsonId
import io.github.zeal18.zio.mongodb.driver.model.ReplaceOptions
import io.greensheep.trading.core.utils.TransientRetry
import io.github.zeal18.zio.mongodb.driver.indexes
import io.github.zeal18.zio.mongodb.driver.sorts.Sort
import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase, filters, updates}
import io.greensheep.trading.core.screens.{BreadthIndicators => MarketBreadth}
import io.greensheep.trading.core.types.{DailyQuoteData, Quote, ScreenStats, Stock, StockDescription, Watchlist}
import zio.*
import zio.stream.ZStream
import zio.interop.reactivestreams.*
import io.github.zeal18.zio.mongodb.bson.codecs.Codec.*
import io.github.zeal18.zio.mongodb.bson.codecs.{CaseClassCodec, Codec, Decoder, Encoder}
import org.bson.BsonWriter
import org.bson.codecs.EncoderContext
import org.bson.codecs.configuration.CodecRegistries

import java.time.LocalDate
import io.greensheep.trading.core.types.AnnualFinancialData
import io.greensheep.trading.core.types.QuarterlyFinancialData
import io.greensheep.trading.core.types.StockFinancials





object MongoDataRepo {

  final case class StoredDailyQuotes(@BsonId ticker: String, quotes: Seq[Quote])

  final case class StoredFinancials(
      @BsonId ticker: String,
      annual: Seq[AnnualFinancialData],
      quarterly: Seq[QuarterlyFinancialData]
  ) {
    def toDomain: StockFinancials = StockFinancials(ticker, annual, quarterly)
  }

  def mergeQuotes(existing: Seq[Quote], incoming: Seq[Quote]): Seq[Quote] = {
    val byDay = scala.collection.mutable.LinkedHashMap.empty[LocalDate, Quote]
    existing.sortBy(_.day.toEpochDay).foreach(quote => byDay.update(quote.day, quote))
    incoming.foreach(quote => byDay.update(quote.day, quote))
    byDay.values.toSeq.sortBy(_.day.toEpochDay)
  }

  def ensureIndexes: ZIO[MongoDatabase, Throwable, Unit] =
    for {
      db <- ZIO.service[MongoDatabase]
      watchlists = db.getCollection[Watchlist]("watchlist")
      breadth = db.getCollection[MarketBreadth]("market-breadth")
      _ <- watchlists.createIndex(indexes.asc("screenName", "day")).retry(TransientRetry.mongo)
      _ <- breadth.createIndex(indexes.asc("date")).retry(TransientRetry.mongo)
    } yield ()




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

      override def hasWatchlistsForDate(day: LocalDate): ZIO[Any, Throwable, Boolean] =
        MongoDataRepo.hasWatchlistsForDate(day).provideLayer(mongoLayer)

      override def getWatchlistsBetween(start: LocalDate, end: LocalDate): ZIO[Any, Throwable, Seq[Watchlist]] =
        MongoDataRepo.getWatchlistsBetween(start, end).provideLayer(mongoLayer)

      override def saveMarketBreadth(indicator: MarketBreadth): ZIO[Any, Throwable, Unit] =
        MongoDataRepo.saveMarketBreadth(indicator).provideLayer(mongoLayer)

      override def hasMarketBreadthForDate(day: LocalDate): ZIO[Any, Throwable, Boolean] =
        MongoDataRepo.hasMarketBreadthForDate(day).provideLayer(mongoLayer)

      override def getMarketBreadthBetween(start: LocalDate, end: LocalDate): ZIO[Any, Throwable, Seq[MarketBreadth]] =
        MongoDataRepo.getMarketBreadthBetween(start, end).provideLayer(mongoLayer)

      override def getMarketBreadthLatest(limit: Int): ZIO[Any, Throwable, Seq[MarketBreadth]] =
        MongoDataRepo.getMarketBreadthLatest(limit).provideLayer(mongoLayer)
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

  def updateFinancials(ticker: String, annual: Seq[AnnualFinancialData], quarterly: Seq[QuarterlyFinancialData]): ZIO[MongoDatabase, Throwable, Unit] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StoredFinancials]("financials")
      financials = StoredFinancials(ticker, annual, quarterly)
      _ <- coll
        .replaceOne(filters.eq("_id", ticker), financials, ReplaceOptions(upsert = true))
        .retry(TransientRetry.mongo)
        .map(_.wasAcknowledged())
    } yield ()
  }

  def hasFinancials(ticker: String): ZIO[MongoDatabase, Throwable, Boolean] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StoredFinancials]("financials")
      count <- coll.countDocuments(filters.eq("_id", ticker)).retry(TransientRetry.mongo).map(_.toInt)
    } yield count > 0
  }

  def presentFianancials(): ZIO[MongoDatabase, Throwable, Seq[StockFinancials]] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StoredFinancials]("financials")
      financials <- coll.find().runToSeq
    } yield financials.map(_.toDomain)
  }


  def getFinancials(ticker: String): ZIO[MongoDatabase, Throwable, StockFinancials] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StoredFinancials]("financials")
      financialsOpt <- coll.find(filters.eq("_id", ticker)).runHead.retry(TransientRetry.mongo)
      stored <- ZIO.fromOption(financialsOpt).orElseFail(new NoSuchElementException(s"No financials found for ticker $ticker"))
    } yield stored.toDomain
  }
  
  def ping: ZIO[MongoDatabase, Throwable, Unit] =
    ZIO.serviceWithZIO[MongoDatabase] { db =>
      db.listCollectionNames().runHead.unit.retry(TransientRetry.mongo)
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
      coll = db.getCollection[StockUniverse]("stock-universe")
      su <- coll.find(filters.eq("_id", "stock-universe")).runHead
      universe <- ZIO.fromOption(su).orElseFail(new NoSuchElementException("No stock universe found"))
    } yield universe
  }

  def clearQuotes(ticker: String): ZIO[MongoDatabase, Throwable, Unit] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StoredDailyQuotes]("daily-quotes")
      _ <- coll.updateOne(filters.eq("_id", ticker), updates.set("quotes", Seq.empty)).map(_.wasAcknowledged())
    } yield ()
  }

  def updateDailyQuotes(ticker: String, quotes: Seq[Quote]): ZIO[MongoDatabase, Throwable, Unit] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StoredDailyQuotes]("daily-quotes")
      existing <- coll.find(filters.eq("_id", ticker)).runHead
      merged = mergeQuotes(existing.map(_.quotes).getOrElse(Seq.empty), quotes)
      _ <- coll
        .replaceOne(filters.eq("_id", ticker), StoredDailyQuotes(ticker, merged), ReplaceOptions(upsert = true))
        .retry(TransientRetry.mongo)
        .map(_.wasAcknowledged())
    } yield ()
  }

  def getPriceData(ticker: String): ZIO[MongoDatabase, Throwable, DailyQuoteData] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[StoredDailyQuotes]("daily-quotes")
      stored <- coll.find(filters.eq("_id", ticker)).runHead
      data <- ZIO.fromOption(stored).orElseFail(new NoSuchElementException(s"No quotes found for ticker $ticker"))
    } yield DailyQuoteData(data.quotes)
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

  def hasWatchlistsForDate(day: LocalDate): ZIO[MongoDatabase, Throwable, Boolean] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[Watchlist]("watchlist")
      count <- coll.countDocuments(filters.eq("day", day)).map(_.toInt)
    } yield count > 0
  }

  def getWatchlistsBetween(start: LocalDate, end: LocalDate): ZIO[MongoDatabase, Throwable, Seq[Watchlist]] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[Watchlist]("watchlist")
      publisher = coll
        .find(filters.and(
          filters.gte("day", start),
          filters.lte("day", end)
        ))
        .sort(Sort.Asc("day"))
      watchlists <- publisher.run.toZIOStream().runCollect.map(_.toSeq)
    } yield watchlists
  }

  def saveMarketBreadth(indicator: MarketBreadth): ZIO[MongoDatabase, Throwable, Unit] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[MarketBreadth]("market-breadth")
      _ <- coll
        .replaceOne(
          filters.eq("date", indicator.date),
          indicator,
          ReplaceOptions(upsert = true)
        )
        .map(_.wasAcknowledged())
    } yield ()
  }

  def hasMarketBreadthForDate(day: LocalDate): ZIO[MongoDatabase, Throwable, Boolean] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[MarketBreadth]("market-breadth")
      count <- coll.countDocuments(filters.eq("date", day)).map(_.toInt)
    } yield count > 0
  }

  def getMarketBreadthBetween(start: LocalDate, end: LocalDate): ZIO[MongoDatabase, Throwable, Seq[MarketBreadth]] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[MarketBreadth]("market-breadth")
      publisher = coll
        .find(filters.and(
          filters.gte("date", start),
          filters.lte("date", end)
        ))
        .sort(Sort.Asc("date"))
      breadth <- publisher.run.toZIOStream().runCollect.map(_.toSeq)
    } yield breadth
  }

  def getMarketBreadthLatest(limit: Int): ZIO[MongoDatabase, Throwable, Seq[MarketBreadth]] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[MarketBreadth]("market-breadth")
      publisher = coll
        .find()
        .sort(Sort.Desc("date"))
        .limit(limit)
      breadth <- publisher.run.toZIOStream().runCollect.map(_.toSeq.reverse)
    } yield breadth
  }

  def getWatchlistLatest(name: String) : ZIO[MongoDatabase, Throwable, Watchlist] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[Watchlist]("watchlist")
      watchlistOpt <- coll.find(filters.eq("screenName", name)).sort(Sort.Desc("day")).runHead
      watchlist <- ZIO.fromOption(watchlistOpt).orElseFail(new NoSuchElementException(s"No watchlist found for name $name"))
    } yield watchlist
  }

  def getWatchlist(name: String, day: LocalDate) : ZIO[MongoDatabase, Throwable, Watchlist] = {
    for {
      db <- ZIO.service[MongoDatabase]
      coll = db.getCollection[Watchlist]("watchlist")
      watchlistOpt <- coll.find(filters.and(
        filters.eq("screenName", name),
        filters.eq("day", day)
      )).runHead
      watchlist <- ZIO.fromOption(watchlistOpt).orElseFail(new NoSuchElementException(s"No watchlist found for name $name and day $day"))
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

  def hasWatchlistsForDate(day: LocalDate): ZIO[Any, Throwable, Boolean]

  def getWatchlistsBetween(start: LocalDate, end: LocalDate): ZIO[Any, Throwable, Seq[Watchlist]]

  def saveMarketBreadth(indicator: MarketBreadth): ZIO[Any, Throwable, Unit]

  def hasMarketBreadthForDate(day: LocalDate): ZIO[Any, Throwable, Boolean]

  def getMarketBreadthBetween(start: LocalDate, end: LocalDate): ZIO[Any, Throwable, Seq[MarketBreadth]]

  def getMarketBreadthLatest(limit: Int): ZIO[Any, Throwable, Seq[MarketBreadth]]
}
