package io.greensheep.trading.dataservice

import io.greensheep.trading.core.types.{MarketId, Markets}
import zio.test.*

object MarketStoreSpec extends ZIOSpecDefault {

  override def spec = suite("MarketStore")(
    test("resolves an enabled market") {
      val result = MarketStore.lookup(Seq(Markets.UsMassive), "US", "Massive")
      assertTrue(result.exists(_.id == MarketId.UsMassive))
    },
    test("unknown market is rejected") {
      val result = MarketStore.lookup(Seq(Markets.UsMassive), "ro", "bvb")
      assertTrue(result.left.exists(_.isInstanceOf[NoSuchElementException]))
    },
    test("blank country or provider is a bad request") {
      val result = MarketStore.lookup(Seq(Markets.UsMassive), " ", "massive")
      assertTrue(result.left.exists(_.isInstanceOf[IllegalArgumentException]))
    }
  )
}
