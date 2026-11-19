package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.{MarketId, StockDescription}
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import zio.*

object CompanyDescriptionService {
  private[service] def enrich(current: StockDescription, reference: Option[StockDescription]): StockDescription =
    reference.filter(_.symbol == current.symbol).fold(current.copy(description = "", sicCode = "", sicDescription = "")) { source =>
      current.copy(
        description = source.description.trim,
        sicCode = source.sicCode,
        sicDescription = source.sicDescription
      )
    }

  def loadMany(store: MarketStore, market: MarketId, tickers: Seq[String]): ZIO[MongoDatabase, Throwable, Seq[StockDescription]] =
    for {
      profile <- ZIO.fromEither(store.resolve(market.country, market.provider))
      current <- MongoDataRepo.getStockDescriptions(tickers)
      // Narrative company metadata can be shared for the same US ticker. Quotes,
      // financials, provider identity and the target collection stay market-specific.
      reference <- if (profile.sources.metadata == "massive" && profile.sources.prices != "massive" && current.nonEmpty)
        MongoDataRepo.getStockDescriptions(current.map(_.symbol))
          .provide(ZLayer.succeed(store.metadataDatabase(profile)))
          .catchAll(_ => ZIO.logWarning("Massive company metadata cache unavailable; serving provider metadata").as(Seq.empty))
      else ZIO.succeed(Seq.empty)
      byTicker = reference.map(stock => stock.symbol -> stock).toMap
      enriched = if (profile.sources.metadata == "massive" && profile.sources.prices != "massive")
        current.map(stock => enrich(stock, byTicker.get(stock.symbol)))
      else if (profile.sources.metadata == "none" || profile.sources.metadata == "alpaca")
        current.map(stock => stock.copy(description = "", sicCode = "", sicDescription = ""))
      else current
    } yield enriched

  def load(store: MarketStore, market: MarketId, ticker: String): ZIO[MongoDatabase, Throwable, StockDescription] =
    loadMany(store, market, Seq(ticker)).flatMap(rows => ZIO.fromOption(rows.headOption)
      .orElseFail(new NoSuchElementException(s"No stock found for symbol $ticker")))
}
