package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.driver.model.{ReplaceOptions, UpdateOptions}
import io.github.zeal18.zio.mongodb.driver.updates.{Update, addEachToSet}
import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase, filters, updates}
import io.greensheep.trading.core.types.{DailyQuoteData, Quote}
import zio.*

object MongoDataRepo {

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
      _ <- ZIO.logInfo(s"Found stock universe $su")
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

}

trait MongoDataRepo {

  def saveStockUniverse(stockUniverse: StockUniverse): ZIO[Any, Throwable, Unit]

  def getStockUniverse: ZIO[Any, Throwable, StockUniverse]

  def hasStockUniverse: ZIO[Any, Throwable, Boolean]

  def updateDailyQuotes(ticker: String, quotes: Seq[Quote]): ZIO[Any, Throwable, Unit]

  def getPriceData(ticker: String): ZIO[Any, Throwable, DailyQuoteData]
}