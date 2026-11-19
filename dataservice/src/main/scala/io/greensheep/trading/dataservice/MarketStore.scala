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

  def financialsDatabase(definition: MarketDefinition): MongoDatabase =
    database(MarketId(definition.id.country, definition.sources.financials))

  def metadataDatabase(definition: MarketDefinition): MongoDatabase =
    if (definition.sources.metadata == definition.sources.prices) database(definition)
    else enabled.find(profile => profile.id.country == definition.id.country && profile.sources.prices == definition.sources.metadata)
      .map(database).getOrElse(database(MarketId(definition.id.country, definition.sources.metadata)))

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
      enabled.find(profile => profile.id == id || profile.aliases.contains(id)).toRight(new NoSuchElementException(s"Unknown market: ${id.id}"))
  }

  def live(client: MongoClient): Either[String, MarketStore] =
    Markets.enabledFromConfig.map(enabled => MarketStore(client, enabled))
}
