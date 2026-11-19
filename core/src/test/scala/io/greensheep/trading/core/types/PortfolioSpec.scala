package io.greensheep.trading.core.types

import zio.test.*
import java.time.Instant
import scala.util.Try

object PortfolioSpec extends ZIOSpecDefault {
  private val now = Instant.parse("2026-11-18T12:00:00Z")
  private def buy(id: String, quantity: BigDecimal, price: BigDecimal, fees: BigDecimal = 0) =
    PortfolioTrade(id, "ABC", "buy", quantity, price, now, fees)
  override def spec = suite("Paper portfolio accounting")(
    test("starts with exactly 100000 cash") {
      assertTrue(Portfolio.replay(Seq.empty) == PortfolioLedger(100000, 0, Vector.empty))
    },
    test("weighted average cost and partial sells include fees without losing cost basis") {
      val first = buy("one", 10, 100, 2)
      val second = buy("two", 10, 120, 2)
      val sale = first.copy(id = "three", side = "sell", quantity = 5, price = 150, fees = 1)
      val state = Portfolio.replay(Seq(first, second, sale))
      assertTrue(state.cash == BigDecimal(98545), state.realizedPnl == BigDecimal(198),
        state.holdings == Vector(Holding("ABC", 15, 1653)),
        state.cash + state.holdings.map(_.costBasis).sum == Portfolio.InitialCapital + state.realizedPnl)
    },
    test("full liquidation removes position and preserves realized return") {
      val first = buy("one", BigDecimal("0.25"), BigDecimal("10.1"))
      val state = Portfolio.replay(Seq(first, first.copy(id = "two", side = "sell", price = 12)))
      assertTrue(state.holdings.isEmpty, state.cash == BigDecimal("100000.475"), state.realizedPnl == BigDecimal("0.475"))
    },
    test("rejects overspending, short sales, invalid precision and future fills") {
      val invalid = Seq(buy("", 1, 10), buy("one", 0, 10), buy("one", 1, -10),
        buy("one", 1, 10, -1), buy("one", BigDecimal("0.000000001"), 10),
        buy("one", 1, 10).copy(executedAt = now.plusSeconds(1)), buy("one", 1, 10).copy(side = "hold"))
      assertTrue(invalid.forall(t => Try(Portfolio.validate(t, now)).isFailure),
        Try(Portfolio.replay(Seq(buy("one", 1001, 100)))).isFailure,
        Try(Portfolio.replay(Seq(buy("one", 1, 10).copy(side = "sell")))).isFailure)
    }
  )
}
