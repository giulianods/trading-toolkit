package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.{PortfolioTrade, Quote}
import zio.test.*
import java.time.{Instant, LocalDate}

object ResearchServicesSpec extends ZIOSpecDefault {
  private val first = LocalDate.parse("2026-11-01")
  private val buy = PortfolioTrade("buy", "ABC", "buy", 10, 100, Instant.parse("2026-11-01T12:00:00Z"))
  private def quote(day: LocalDate, close: Double) = Quote(day, close, close, close, close, 100)
  override def spec = suite("Portfolio valuation")(
    test("history never uses future fills or quotes and current positions use last close") {
      val sell = buy.copy(id = "sell", side = "sell", quantity = 5, price = 130, executedAt = buy.executedAt.plusSeconds(86400))
      val quotes = Map("ABC" -> Seq(quote(first.minusDays(1), 500), quote(first, 110), quote(first.plusDays(1), 130)))
      val view = ResearchServices.valuePortfolio("ro", Vector(buy, sell), quotes, first.plusDays(1))
      assertTrue(view.currency == "RON", view.cash == 99650, view.equity == 100300,
        view.realizedPnl == 150, view.unrealizedPnl == 150, view.positions.head.currentPrice == 130,
        view.history.map(_.equity) == Seq(BigDecimal(100000), BigDecimal(100100), BigDecimal(100300)),
        view.trades.map(_.id) == Seq("sell", "buy"))
    },
    test("missing or pre-fill quotes use last fill explicitly") {
      val view = ResearchServices.valuePortfolio("ca", Vector(buy), Map("ABC" -> Seq(quote(first.minusDays(1), 500))), first)
      assertTrue(view.currency == "CAD", view.equity == 100000,
        view.positions.head.priceSource == "last_fill", view.positions.head.quoteDate.isEmpty,
        view.positions.head.currentPrice == 100)
    },
    test("all supported countries use local currency without changing valuation") {
      val currencies = Seq("us" -> "USD", "ro" -> "RON", "ca" -> "CAD", "de" -> "EUR", "jp" -> "JPY")
      val views = currencies.map { case (country, currency) =>
        val view = ResearchServices.valuePortfolio(country, Vector(buy), Map("ABC" -> Seq(quote(first, 110))), first)
        (view, currency)
      }
      assertTrue(views.forall { case (view, currency) =>
        view.currency == currency && view.initialCapital == 100000 && view.cash == 99000 && view.equity == 100100
      })
    },
    test("empty US account has initial equity and no invented history") {
      val view = ResearchServices.valuePortfolio("us", Vector.empty, Map.empty, first)
      assertTrue(view.currency == "USD", view.equity == 100000, view.cash == 100000, view.history.isEmpty)
    }
  )
}
