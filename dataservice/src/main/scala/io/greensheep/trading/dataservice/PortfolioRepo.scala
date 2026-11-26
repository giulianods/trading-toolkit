package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.bson.annotations.BsonId
import io.github.zeal18.zio.mongodb.bson.codecs.Codec
import io.github.zeal18.zio.mongodb.bson.codecs.Codec.*
import io.github.zeal18.zio.mongodb.driver.{MongoDatabase, filters}
import io.github.zeal18.zio.mongodb.driver.sorts.Sort
import io.greensheep.trading.core.types.*
import com.mongodb.{MongoException, ReadConcern, WriteConcern, TransactionOptions}
import com.mongodb.reactivestreams.client.ClientSession
import org.bson.BsonBinaryWriter
import org.bson.io.BasicOutputBuffer
import org.bson.codecs.EncoderContext
import zio.*
import zio.json.*
import zio.interop.reactivestreams.*
import java.time.Instant

/** V2 is isolated from the untouched v1 portfolio/ledger document. All writers must be
  * stopped before enabling migration; v1 binaries cannot observe the v2 write fence.
  * JSON strings deliberately preserve arbitrary decimal precision (not BSON doubles).
  */
object PortfolioRepo {
  val MaxTrades = 500
  val MaxBytes = 4 * 1024 * 1024
  // Bound the checkpoint independently of historical trade volume.
  val MaxOpenPositions = 10000
  val MaxCheckpointBytes = 4 * 1024 * 1024
  final case class Bucket(@BsonId id: Long, trades: Vector[String], closed: Boolean)
  final case class Head(@BsonId id: String, count: Long, bucket: Long, checkpoint: String,
      lastTime: String, ready: Boolean, legacyRevision: Long, legacyHash: String)
  final case class Receipt(@BsonId id: String, json: String)
  object Bucket { implicit val codec: Codec[Bucket] = Codec.derived[Bucket] }
  object Head { implicit val codec: Codec[Head] = Codec.derived[Head] }
  object Receipt { implicit val codec: Codec[Receipt] = Codec.derived[Receipt] }
  final case class Conflict(message: String) extends Exception(message)
  private val headId = filters.eq("_id", "head")
  private val options = TransactionOptions.builder().readConcern(ReadConcern.SNAPSHOT)
    .writeConcern(WriteConcern.MAJORITY).build()

  private def decode[A: JsonDecoder](s: String): Task[A] =
    ZIO.fromEither(s.fromJson[A]).mapError(e => new IllegalStateException(e))

  /** Size the actual collection codec output, including _id, array keys and UTF-8. */
  def bsonSize(db: MongoDatabase, bucket: Bucket): Int = {
    val collection = db.getCollection[Bucket]("portfolio_buckets")
    val buffer = new BasicOutputBuffer()
    val writer = new BsonBinaryWriter(buffer)
    try {
      collection.codecRegistry.get(classOf[Bucket]).encode(writer, bucket,
        EncoderContext.builder().isEncodingCollectibleDocument(true).build())
      buffer.getSize
    } finally { writer.close(); buffer.close() }
  }

  private def transient(e: Throwable): Boolean = e match {
    case m: MongoException => m.hasErrorLabel("TransientTransactionError") || m.getCode == 11000
    case _ => false
  }
  private def unknown(e: Throwable): Boolean = e match {
    case m: MongoException => m.hasErrorLabel("UnknownTransactionCommitResult")
    case _ => false
  }

  private[dataservice] def commitWithRetry(commit: Task[Unit], remaining: Int = 20): Task[Unit] =
    commit.catchAll { e =>
      if (unknown(e) && remaining > 0) ZIO.sleep(20.millis) *> commitWithRetry(commit, remaining - 1)
      else ZIO.fail(e)
    }

  /** Unknown commits retry only commit on the SAME session/transaction. Never replay
    * an uncertain transaction. If retries exhaust the caller can retry its stable ID.
    */
  private[dataservice] def transaction[A](db: MongoDatabase)(body: ClientSession => Task[A]): Task[A] = {
    def attempt(left: Int): Task[A] = ZIO.scoped {
      for {
        session <- db.startSession()
        _ <- ZIO.attempt(session.startTransaction(options))
        result <- body(session).onError(_ => session.abortTransaction().toZIOStream().runDrain.ignore)
        committed <- commitWithRetry(ZIO.suspendSucceed(session.commitTransaction().toZIOStream().runDrain)).as(result)
      } yield committed
    }.catchAll(e => if (transient(e) && !unknown(e) && left > 0)
      ZIO.sleep(10.millis) *> attempt(left - 1) else ZIO.fail(e))
    attempt(40)
  }

  private def hash(s: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  private def legacy(db: MongoDatabase): Task[Option[ResearchRepo.Ledger]] =
    db.getCollection[ResearchRepo.Ledger]("portfolio").find(filters.eq("_id", "ledger")).runHead

  /** Resumable, one bounded transaction per fill. Until verification and atomic ready
    * switch complete, readers still see v1 and new writers fail closed.
    */
  def migrate: ZIO[MongoDatabase, Throwable, Unit] = for {
    db <- ZIO.service[MongoDatabase]
    source <- legacy(db)
    json = source.fold("[]")(_.json)
    revision = source.fold(-1L)(_.revision)
    trades <- decode[Vector[PortfolioTrade]](json)
    _ <- ZIO.attempt {
      require(trades.map(_.id).distinct.size == trades.size, "Legacy duplicate trade IDs")
      trades.foreach(Portfolio.validate(_, Instant.now()))
      require(trades.zip(trades.drop(1)).forall((a,b) => !b.executedAt.isBefore(a.executedAt)), "Legacy chronology invalid")
      Portfolio.replay(trades)
    }
    completed <- transaction(db) { session =>
      val heads = db.getCollection[Head]("portfolio_heads")
      heads.find(session, headId).runHead.flatMap {
        case Some(h) => ZIO.attempt(require(h.legacyHash == hash(json) && h.legacyRevision == revision,
          "Legacy changed during migration; stop old writers and restore the migration source")).as(h.ready)
        case None => heads.insertOne(session, Head("head", 0, 0, Portfolio.replay(Vector.empty).toJson,
          "", false, revision, hash(json))).as(false)
      }
    }
    _ <- (for {
      _ <- ZIO.foreachDiscard(trades)(t => appendInternal(db, t, migrating = true))
      _ <- transaction(db) { session => for {
      h <- db.getCollection[Head]("portfolio_heads").find(session, headId).runHead.someOrFail(new IllegalStateException("Missing head"))
      current <- db.getCollection[ResearchRepo.Ledger]("portfolio").find(session, filters.eq("_id", "ledger")).runHead
      buckets <- db.getCollection[Bucket]("portfolio_buckets").find(session).sort(Sort.Asc("_id")).run.toZIOStream().runCollect
      decoded <- ZIO.foreach(buckets.flatMap(_.trades))(decode[PortfolioTrade])
      state <- decode[PortfolioLedger](h.checkpoint)
      _ <- ZIO.attempt {
        require(current == source, "Legacy changed during migration")
        require(decoded.toVector == trades, s"Migration trade verification failed: stored=${decoded.size}, source=${trades.size}, firstMismatch=${decoded.toVector.zip(trades).indexWhere((a, b) => a != b)}, buckets=${buckets.map(b => s"${b.id}:${b.trades.size}").mkString(",")}")
        require(h.count == trades.size, "Migration count verification failed")
        require(state == Portfolio.replay(trades), "Migration checkpoint verification failed")
        require(buckets.forall(b => b.trades.size <= MaxTrades && bsonSize(db,b) <= MaxBytes), "Invalid migrated bucket")
      }
      _ <- db.getCollection[Head]("portfolio_heads").replaceOne(session, headId, h.copy(ready = true))
      } yield () }
    } yield ()).unless(completed)
  } yield ()

  private def ensure(db: MongoDatabase): Task[Unit] =
    db.getCollection[Head]("portfolio_heads").find(headId).runHead.flatMap {
      case Some(h) if h.ready => ZIO.unit
      case Some(_) => ZIO.fail(new IllegalStateException("Portfolio migration incomplete; run migration with old writers stopped"))
      case None => transaction(db) { session =>
        val heads = db.getCollection[Head]("portfolio_heads")
        heads.find(session, headId).runHead.flatMap {
          case Some(h) if h.ready => ZIO.unit
          case Some(_) => ZIO.fail(new IllegalStateException("Portfolio migration incomplete"))
          case None => for {
            source <- db.getCollection[ResearchRepo.Ledger]("portfolio").find(session, filters.eq("_id", "ledger")).runHead
            json = source.fold("[]")(_.json)
            rows <- decode[Vector[PortfolioTrade]](json)
            _ <- ZIO.fail(new IllegalStateException("Legacy portfolio requires explicit migration with old writers stopped")).when(rows.nonEmpty)
            _ <- heads.insertOne(session, Head("head", 0, 0, Portfolio.replay(Vector.empty).toJson,
              "", true, source.fold(-1L)(_.revision), hash(json)))
          } yield ()
        }
      }
    }

  def append(trade: PortfolioTrade): ZIO[MongoDatabase, Throwable, Boolean] = for {
    db <- ZIO.service[MongoDatabase]
    _ <- ensure(db)
    result <- appendInternal(db, trade, migrating = false)
  } yield result

  private def appendInternal(db: MongoDatabase, trade: PortfolioTrade, migrating: Boolean): Task[Boolean] =
    transaction(db) { session =>
      val heads = db.getCollection[Head]("portfolio_heads")
      val buckets = db.getCollection[Bucket]("portfolio_buckets")
      val receipts = db.getCollection[Receipt]("portfolio_receipts")
      for {
        h <- heads.find(session, headId).runHead.someOrFail(new IllegalStateException("Missing portfolio head"))
        _ <- ZIO.fail(new IllegalStateException("Portfolio migration incomplete")).unless(h.ready || migrating)
        prior <- receipts.find(session, filters.eq("_id", trade.id)).runHead
        result <- prior match {
          case Some(r) => decode[PortfolioTrade](r.json).flatMap(existing =>
            if (existing == trade) ZIO.succeed(false) else ZIO.fail(Conflict("Trade id already exists with different details")))
          case None => for {
            state <- decode[PortfolioLedger](h.checkpoint)
            next <- ZIO.attempt {
              Portfolio.validate(trade, Instant.now())
              require(h.lastTime.isEmpty || !trade.executedAt.isBefore(Instant.parse(h.lastTime)), "Trades must be submitted in chronological order")
              val updated = Portfolio.applyTrade(state, trade)
              require(updated.holdings.size <= MaxOpenPositions, "Portfolio open-position limit reached (10000)")
              require(updated.toJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MaxCheckpointBytes,
                "Portfolio checkpoint size limit reached (4 MiB)")
              updated
            }
            old <- buckets.find(session, filters.eq("_id", h.bucket)).runHead
            active = old.getOrElse(Bucket(h.bucket, Vector.empty, false))
            candidate = active.copy(trades = active.trades :+ trade.toJson)
            fits <- ZIO.attempt(!active.closed && candidate.trades.size <= MaxTrades && bsonSize(db,candidate) <= MaxBytes)
            target = if (fits) candidate else Bucket(h.bucket + 1, Vector(trade.toJson), false)
            _ <- ZIO.attempt(require(bsonSize(db,target) <= MaxBytes, "Trade exceeds bucket BSON limit"))
            _ <- if (!fits && !active.closed) buckets.replaceOne(session, filters.eq("_id", active.id), active.copy(closed = true)).unit else ZIO.unit
            _ <- if (fits && old.nonEmpty) buckets.replaceOne(session, filters.eq("_id", target.id), target).unit
                 else buckets.insertOne(session, target).unit
            _ <- receipts.insertOne(session, Receipt(trade.id, trade.toJson))
            // Every writer updates this single checkpoint: snapshot write conflicts prevent overspending.
            _ <- heads.replaceOne(session, headId, h.copy(count = h.count + 1, bucket = target.id,
              checkpoint = next.toJson, lastTime = trade.executedAt.toString))
          } yield true
        }
      } yield result
    }

  def trades: ZIO[MongoDatabase, Throwable, Vector[PortfolioTrade]] = for {
    db <- ZIO.service[MongoDatabase]
    h <- db.getCollection[Head]("portfolio_heads").find(headId).runHead
    result <- if (h.exists(_.ready)) transaction(db) { session => for {
      rows <- db.getCollection[Bucket]("portfolio_buckets").find(session).sort(Sort.Asc("_id")).run.toZIOStream().runCollect
      values <- ZIO.foreach(rows.flatMap(_.trades))(decode[PortfolioTrade])
    } yield values.toVector }
    else legacy(db).flatMap(row => decode[Vector[PortfolioTrade]](row.fold("[]")(_.json)))
  } yield result
}
