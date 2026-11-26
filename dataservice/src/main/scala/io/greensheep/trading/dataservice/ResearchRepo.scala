package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.bson.annotations.BsonId
import io.github.zeal18.zio.mongodb.bson.codecs.Codec.*
import io.github.zeal18.zio.mongodb.driver.{MongoDatabase, filters}
import io.github.zeal18.zio.mongodb.driver.model.ReplaceOptions
import io.github.zeal18.zio.mongodb.driver.sorts.Sort
import zio.*
import zio.interop.reactivestreams.*

/** Kept in each profile's database. JSON payloads preserve decimal ledger values exactly. */
object ResearchRepo {
  final case class Report(@BsonId id: String, title: String, markdown: String, asOf: String, createdAt: String)
  final case class Ledger(@BsonId id: String, revision: Long, json: String)
  object Ledger {
    implicit val codec: io.github.zeal18.zio.mongodb.bson.codecs.Codec[Ledger] =
      io.github.zeal18.zio.mongodb.bson.codecs.Codec.derived[Ledger]
  }

  def reports(limit: Int, offset: Int): ZIO[MongoDatabase, Throwable, Seq[Report]] =
    ZIO.serviceWithZIO[MongoDatabase](_.getCollection[Report]("reports").find()
      .sort(Sort.Desc("createdAt")).skip(offset).limit(limit).run.toZIOStream().runCollect.map(_.toSeq))

  def report(id: String): ZIO[MongoDatabase, Throwable, Option[Report]] =
    ZIO.serviceWithZIO[MongoDatabase](_.getCollection[Report]("reports").find(filters.eq("_id", id)).runHead)

  def insertReport(report: Report): ZIO[MongoDatabase, Throwable, Unit] =
    ZIO.serviceWithZIO[MongoDatabase](_.getCollection[Report]("reports").insertOne(report).unit)

  def ledger: ZIO[MongoDatabase, Throwable, Ledger] = for {
    db <- ZIO.service[MongoDatabase]
    collection = db.getCollection[Ledger]("portfolio")
    _ <- collection.updateOne(filters.eq("_id", "ledger"),
      io.github.zeal18.zio.mongodb.driver.updates.combine(
        io.github.zeal18.zio.mongodb.driver.updates.setOnInsert("revision", 0L),
        io.github.zeal18.zio.mongodb.driver.updates.setOnInsert("json", "[]")),
      io.github.zeal18.zio.mongodb.driver.model.UpdateOptions(upsert = true))
      .catchSome { case e: com.mongodb.MongoException if e.getCode == 11000 => ZIO.unit }
    result <- collection.find(filters.eq("_id", "ledger")).runHead.someOrFail(new IllegalStateException("Missing ledger"))
  } yield result

  def saveLedger(previous: Ledger, json: String): ZIO[MongoDatabase, Throwable, Boolean] =
    ZIO.serviceWithZIO[MongoDatabase](_.getCollection[Ledger]("portfolio")
      .replaceOne(filters.and(filters.eq("_id", previous.id), filters.eq("revision", previous.revision)),
        Ledger(previous.id, previous.revision + 1, json), ReplaceOptions(upsert = false))
      .map(_.getModifiedCount == 1))
}
