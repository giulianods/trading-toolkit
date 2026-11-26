package io.greensheep.trading.dataservice

import com.mongodb.MongoException
import io.github.zeal18.zio.mongodb.driver.{MongoClient, filters}
import zio.*
import zio.test.*
import java.util.UUID

object PortfolioTransactionSpec extends ZIOSpecDefault {
  private def uncertain = {
    val error = new MongoException(91, "simulated uncertain commit")
    error.addLabel("UnknownTransactionCommitResult")
    error
  }
  def spec = suite("Portfolio transaction recovery")(
    test("unknown commit retries only commit and is bounded") {
      for {
        calls <- Ref.make(0)
        _ <- PortfolioRepo.commitWithRetry(calls.updateAndGet(_ + 1).flatMap(n => if (n < 3) ZIO.fail(uncertain) else ZIO.unit))
        successCount <- calls.get
        _ <- calls.set(0)
        exhausted <- PortfolioRepo.commitWithRetry(calls.update(_ + 1) *> ZIO.fail(uncertain), 2).exit
        exhaustedCount <- calls.get
        _ <- calls.set(0)
        ordinary <- PortfolioRepo.commitWithRetry(calls.update(_ + 1) *> ZIO.fail(new RuntimeException("ordinary"))).exit
        ordinaryCount <- calls.get
      } yield assertTrue(successCount == 3, exhausted.isFailure, exhaustedCount == 3, ordinary.isFailure, ordinaryCount == 1)
    },
    test("failure after bucket and receipt writes aborts all transaction changes") {
      ZIO.scoped {
        for {
          client <- ZIO.service[MongoClient]
          db <- ZIO.acquireRelease(ZIO.succeed(client.getDatabase("transaction_test_" + UUID.randomUUID.toString.replace("-", ""))))(_.drop().orDie)
          _ <- PortfolioRepo.migrate.provideEnvironment(ZEnvironment(db))
          result <- PortfolioRepo.transaction(db) { session => for {
            _ <- db.getCollection[PortfolioRepo.Bucket]("portfolio_buckets").insertOne(session, PortfolioRepo.Bucket(0, Vector("test"), false))
            _ <- db.getCollection[PortfolioRepo.Receipt]("portfolio_receipts").insertOne(session, PortfolioRepo.Receipt("test", "test"))
            _ <- ZIO.fail(new RuntimeException("injected interruption before checkpoint"))
          } yield () }.exit
          bucket <- db.getCollection[PortfolioRepo.Bucket]("portfolio_buckets").find().runHead
          receipt <- db.getCollection[PortfolioRepo.Receipt]("portfolio_receipts").find().runHead
          head <- db.getCollection[PortfolioRepo.Head]("portfolio_heads").find(filters.eq("_id", "head")).runHead
        } yield assertTrue(result.isFailure, bucket.isEmpty, receipt.isEmpty, head.exists(_.count == 0))
      }.provide(MongoClient.live(sys.env.getOrElse("RESEARCH_TEST_MONGODB_URL", "mongodb://127.0.0.1:1")))
    } @@ TestAspect.ifEnvSet("RESEARCH_TEST_MONGODB_URL")
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
}
