package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase, filters}
import io.github.zeal18.zio.mongodb.driver.sorts.Sort
import io.github.zeal18.zio.mongodb.bson.codecs.Codec.*
import io.greensheep.trading.core.types.*
import io.greensheep.trading.dataservice.{PortfolioRepo, ResearchRepo}
import zio.*
import zio.json.*
import zio.test.*
import zio.interop.reactivestreams.*
import java.time.Instant
import java.util.UUID

object PortfolioBucketIntegrationSpec extends ZIOSpecDefault {
  private val url = sys.env.getOrElse("RESEARCH_TEST_MONGODB_URL", "mongodb://127.0.0.1:1")
  private def fill(i: Int, notes: String = "") = PortfolioTrade(s"fill-$i", "ABC", "buy",
    BigDecimal("0.00000001"), BigDecimal("0.12345678"), Instant.parse("2026-01-01T00:00:00Z"), notes = notes)
  private def isolated[A](effect: ZIO[MongoDatabase, Throwable, A]): Task[A] = ZIO.scoped {
    for {
      client <- ZIO.service[MongoClient]
      db <- ZIO.acquireRelease(ZIO.succeed(client.getDatabase("bucket_test_" + UUID.randomUUID.toString.replace("-", ""))))(_.drop().orDie)
      result <- effect.provideEnvironment(ZEnvironment(db))
    } yield result
  }.provide(MongoClient.live(url))
  private def buckets = ZIO.serviceWithZIO[MongoDatabase](_.getCollection[PortfolioRepo.Bucket]("portfolio_buckets")
    .find().sort(Sort.Asc("_id")).run.toZIOStream().runCollect)

  def spec = suite("Transactional portfolio buckets")(
    test("500 count rollover, immutable closed buckets, global IDs and exact checkpoint") {
      isolated { for {
        _ <- PortfolioRepo.migrate
        _ <- ZIO.foreachDiscard(0 to 500)(i => PortfolioRepo.append(fill(i)))
        before <- buckets
        duplicate <- PortfolioRepo.append(fill(0))
        conflict <- PortfolioRepo.append(fill(0).copy(price = 2)).exit
        _ <- PortfolioRepo.append(fill(501))
        after <- buckets
        rows <- PortfolioRepo.trades
        db <- ZIO.service[MongoDatabase]
        h <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead.someOrFail(new Exception("head"))
      } yield assertTrue(before.map(_.trades.size).toVector == Vector(500,1), before.head.closed,
        after.head == before.head, !duplicate, conflict.isFailure, rows == (0 to 501).map(fill(_)).toVector,
        h.checkpoint.fromJson[PortfolioLedger] == Right(Portfolio.replay(rows))) }
    },
    test("actual UTF-8 BSON byte rollover and aggregate history over 16MB") {
      isolated { for {
        _ <- PortfolioRepo.migrate
        notes = "界" * 4000
        _ <- ZIO.foreachDiscard(0 until 1450)(i => PortfolioRepo.append(fill(i,notes)))
        rows <- PortfolioRepo.trades
        bs <- buckets
        db <- ZIO.service[MongoDatabase]
        sizes = bs.map(PortfolioRepo.bsonSize(db,_))
      } yield assertTrue(rows.size == 1450, bs.size >= 5, sizes.sum > 16 * 1024 * 1024,
        sizes.forall(_ <= PortfolioRepo.MaxBytes), bs.forall(_.trades.size <= 500),
        bs.dropRight(1).forall(b => b.closed && b.trades.size < 500 &&
          PortfolioRepo.bsonSize(db,b.copy(trades = b.trades :+ fill(9999,notes).toJson)) > PortfolioRepo.MaxBytes)) }
    },
    test("concurrent unique IDs cannot overspend; concurrent duplicate creates once") {
      isolated { for {
        _ <- PortfolioRepo.migrate
        first <- ZIO.foreachPar(1 to 8)(_ => PortfolioRepo.append(fill(0)).either)
        results <- ZIO.foreachPar(1 to 8)(i => PortfolioRepo.append(fill(i).copy(quantity = 1, price = 60000)).either)
        rows <- PortfolioRepo.trades
        earlier <- PortfolioRepo.append(fill(99).copy(executedAt = fill(0).executedAt.minusSeconds(1))).exit
      } yield assertTrue(first.count(_ == Right(true)) == 1, first.count(_ == Right(false)) == 7,
        results.count(_.isRight) == 1, rows.size == 2, Portfolio.replay(rows).cash >= 0, earlier.isFailure) }
    },
    test("open-position limit rejects atomically without changing the checkpoint") {
      isolated { for {
        db <- ZIO.service[MongoDatabase]
        _ <- PortfolioRepo.migrate
        h <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead.someOrFail(new Exception("head"))
        checkpoint = PortfolioLedger(100000, 0, (0 until PortfolioRepo.MaxOpenPositions).map(i => Holding(s"T$i", 1, 1)).toVector)
        seeded = h.copy(checkpoint = checkpoint.toJson)
        _ <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").replaceOne(filters.eq("_id", "head"), seeded)
        rejected <- PortfolioRepo.append(fill(0)).exit
        after <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead
        rows <- PortfolioRepo.trades
      } yield assertTrue(rejected.isFailure, after.contains(seeded), rows.isEmpty) }
    },
    test("empty legacy provenance survives initialization and migration rerun") {
      isolated { for {
        db <- ZIO.service[MongoDatabase]
        _ <- db.getCollection[ResearchRepo.Ledger]("portfolio").insertOne(ResearchRepo.Ledger("ledger", 42, "[]"))
        _ <- PortfolioRepo.append(fill(1))
        _ <- PortfolioRepo.migrate
        rows <- PortfolioRepo.trades
        head <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead
      } yield assertTrue(rows == Vector(fill(1)), head.exists(_.legacyRevision == 42)) }
    },
    test("checkpoint decimal round trips match replay through partial sells and liquidation") {
      isolated { for {
        db <- ZIO.service[MongoDatabase]
        fills = Vector(fill(0).copy(quantity = 3, price = 1, fees = BigDecimal("0.01")),
          fill(1).copy(quantity = 4, price = 2),
          fill(2).copy(side = "sell", quantity = 2, price = 3, fees = BigDecimal("0.02")),
          fill(3).copy(side = "sell", quantity = 5, price = 3))
        checks <- ZIO.foreach(fills.indices) { i => for {
          _ <- PortfolioRepo.append(fills(i))
          head <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead.someOrFail(new Exception("head"))
        } yield head.checkpoint.fromJson[PortfolioLedger] == Right(Portfolio.replay(fills.take(i + 1))) }
      } yield assertTrue(checks.forall(identity)) }
    },
    test("partially copied migration resumes and failed verification stays read-only") {
      isolated { for {
        db <- ZIO.service[MongoDatabase]
        prefix = Vector(fill(0), fill(1))
        all = prefix :+ fill(2)
        _ <- db.getCollection[ResearchRepo.Ledger]("portfolio").insertOne(ResearchRepo.Ledger("ledger", 7, prefix.toJson))
        _ <- PortfolioRepo.migrate
        h <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead.someOrFail(new Exception("head"))
        source = ResearchRepo.Ledger("ledger", 8, all.toJson)
        digest = java.security.MessageDigest.getInstance("SHA-256").digest(source.json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
        _ <- db.getCollection[ResearchRepo.Ledger]("portfolio").replaceOne(filters.eq("_id", "ledger"), source)
        paused = h.copy(ready = false, legacyRevision = 8, legacyHash = digest)
        _ <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").replaceOne(filters.eq("_id", "head"), paused)
        blocked <- PortfolioRepo.append(fill(9)).exit
        readable <- PortfolioRepo.trades
        _ <- PortfolioRepo.migrate
        resumed <- PortfolioRepo.trades
        ready <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead.someOrFail(new Exception("head"))
        _ <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").replaceOne(filters.eq("_id", "head"), ready.copy(ready = false, checkpoint = Portfolio.replay(Vector.empty).toJson))
        failed <- PortfolioRepo.migrate.exit
        after <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find().runHead
        stillReadable <- PortfolioRepo.trades
      } yield assertTrue(blocked.isFailure, readable == all, resumed == all, failed.isFailure,
        after.exists(!_.ready), stillReadable == all) }
    },
    test("legacy migration preserves source, is repeatable, verifies checkpoints and refuses old-writer changes") {
      isolated { for {
        db <- ZIO.service[MongoDatabase]
        original = ResearchRepo.Ledger("ledger", 42, (0 until 505).map(fill(_)).toVector.toJson)
        _ <- db.getCollection[ResearchRepo.Ledger]("portfolio").insertOne(original)
        blocked <- PortfolioRepo.append(fill(900)).exit
        legacy <- PortfolioRepo.trades
        _ <- PortfolioRepo.migrate
        _ <- PortfolioRepo.migrate
        migrated <- PortfolioRepo.trades
        saved <- db.getCollection[ResearchRepo.Ledger]("portfolio").find().runHead
        _ <- PortfolioRepo.append(fill(900))
        _ <- PortfolioRepo.migrate
        afterAppend <- PortfolioRepo.trades
        _ <- db.getCollection[ResearchRepo.Ledger]("portfolio").replaceOne(filters.eq("_id","ledger"), original.copy(revision = 43))
        changed <- PortfolioRepo.migrate.exit
      } yield assertTrue(blocked.isFailure, migrated == legacy, saved.contains(original), afterAppend == (legacy :+ fill(900)), changed.isFailure) }
    }
  ) @@ TestAspect.ifEnvSet("RESEARCH_TEST_MONGODB_URL") @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(300.seconds)
}
