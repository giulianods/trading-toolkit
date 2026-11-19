package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.Markets
import zio.test.*

object MarketDataServicesSpec extends ZIOSpecDefault {
  override def spec = suite("Provider price isolation")(
    test("rejects changing the price feed over existing history while accepting legacy source names") {
      assertTrue(MarketDataServices.validateStoredPrices(Markets.UsAlpaca, Some("massive")).isLeft,
        MarketDataServices.validateStoredPrices(Markets.UsAlpaca, Some("alpaca")).isRight,
        MarketDataServices.validateStoredPrices(Markets.UsFinnhub, Some("finnhub")).isRight,
        MarketDataServices.validateStoredPrices(Markets.UsMassive, Some("polygon")).isRight,
        MarketDataServices.validateStoredPrices(Markets.UsAlpaca, None).isRight)
    }
  )
}
