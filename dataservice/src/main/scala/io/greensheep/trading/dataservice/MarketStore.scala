package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.driver.{MongoClient, MongoDatabase}
import io.greensheep.trading.core.types.{MarketDefinition, MarketId, Markets}
import zio.*

final class MarketStore(client: MongoClient, val enabled: Seq[MarketDefinition]) {

  def resolve(country: String, provider: String): Either[Throwable, MarketDefinition] =
    MarketStore.lookup(enabled, country, provider)

  def database(id: MarketId): MongoDatabase =
    client.getDatabase(id.databaseName)

  def database(definition: MarketDefinition): MongoDatabase =
    database(definition.id)

  def databaseZIO(country: String, provider: String): IO[Throwable, MongoDatabase] =
    ZIO.fromEither(resolve(country, provider)).map(database)
}

object MarketStore {

  def lookup(
      enabled: Seq[MarketDefinition],
      country: String,
      provider: String
  ): Either[Throwable, MarketDefinition] = {
    val id = MarketId.fromPath(country, provider)
    if (id.country.isEmpty || id.provider.isEmpty)
      Left(new IllegalArgumentException("country and provider are required"))
    else
      enabled.find(_.id == id).toRight(new NoSuchElementException(s"Unknown market: ${id.id}"))
  }

  def live(client: MongoClient): Either[String, MarketStore] =
    Markets.enabledFromConfig.map(enabled => MarketStore(client, enabled))
}
