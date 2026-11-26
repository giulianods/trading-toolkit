package io.greensheep.trading.service

import io.github.zeal18.zio.mongodb.driver.MongoClient
import io.greensheep.trading.core.utils.LocalConfig
import io.greensheep.trading.dataservice.PortfolioRepo
import zio.*

/** Offline operator command. Never starts HTTP, schedulers or external data providers. */
object PortfolioMigration extends ZIOAppDefault {
  def run = for {
    args <- getArgs
    _ <- ZIO.attempt(require(args.size == 2 && args(1) == "--old-writers-stopped",
      "Usage: PortfolioMigration <database-name> --old-writers-stopped (stop ALL old and new API writers first)"))
    _ <- ZIO.attempt(require(args.head.matches("[A-Za-z0-9_-]+"), "Invalid database name"))
    url = LocalConfig.get("mongodb.url").getOrElse("mongodb://127.0.0.1:27017")
    _ <- ZIO.serviceWithZIO[MongoClient](client => PortfolioRepo.migrate
      .provideEnvironment(ZEnvironment(client.getDatabase(args.head)))).provide(MongoClient.live(url))
    _ <- Console.printLine("Portfolio migration verified and activated; legacy portfolio/ledger preserved unchanged.")
  } yield ()
}
