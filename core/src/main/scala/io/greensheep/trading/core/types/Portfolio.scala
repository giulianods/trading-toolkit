package io.greensheep.trading.core.types

import zio.json.*
import java.time.Instant

final case class PortfolioTrade(id: String, ticker: String, side: String, quantity: BigDecimal,
    price: BigDecimal, executedAt: Instant, fees: BigDecimal = 0, notes: String = "")
object PortfolioTrade {
  implicit val codec: JsonCodec[PortfolioTrade] = DeriveJsonCodec.gen[PortfolioTrade]
}

final case class Holding(ticker: String, quantity: BigDecimal, costBasis: BigDecimal)
final case class PortfolioLedger(cash: BigDecimal, realizedPnl: BigDecimal, holdings: Vector[Holding])

/** Cash-only, long-only paper ledger. Average cost includes buy fees; sell fees reduce realized P/L. */
object Portfolio {
  val InitialCapital: BigDecimal = 100000

  def validate(trade: PortfolioTrade, now: Instant): Unit = {
    require(trade.id.matches("[A-Za-z0-9_-]{1,100}"), "id must contain 1-100 letters, digits, underscores or hyphens")
    require(trade.ticker.nonEmpty && trade.ticker.length <= 64 && !trade.ticker.exists(_.isWhitespace), "Invalid ticker")
    require(Set("buy", "sell")(trade.side), "side must be buy or sell")
    require(trade.quantity > 0 && trade.quantity <= BigDecimal("1000000000"), "quantity must be positive and at most 1000000000")
    require(trade.price > 0 && trade.price <= BigDecimal("1000000000"), "price must be positive and at most 1000000000")
    require(trade.fees >= 0 && trade.fees <= InitialCapital, "Invalid fees")
    require(trade.quantity.scale <= 8 && trade.price.scale <= 8 && trade.fees.scale <= 8, "Use at most 8 decimal places")
    require(!trade.executedAt.isAfter(now), "executedAt cannot be in the future")
    require(trade.notes.length <= 4000, "notes must be at most 4000 characters")
  }

  def replay(trades: Seq[PortfolioTrade]): PortfolioLedger =
    trades.foldLeft(PortfolioLedger(InitialCapital, 0, Vector.empty)) { (state, trade) =>
      val holding = state.holdings.find(_.ticker == trade.ticker).getOrElse(Holding(trade.ticker, 0, 0))
      val gross = trade.quantity * trade.price
      val (cash, realized, updated) = if (trade.side == "buy") {
        require(state.cash >= gross + trade.fees, "Insufficient cash")
        (state.cash - gross - trade.fees, state.realizedPnl,
          holding.copy(quantity = holding.quantity + trade.quantity, costBasis = holding.costBasis + gross + trade.fees))
      } else {
        require(holding.quantity >= trade.quantity, s"Insufficient shares of ${trade.ticker}")
        val cost = holding.costBasis * trade.quantity / holding.quantity
        val nextCash = state.cash + gross - trade.fees
        require(nextCash >= 0, "Insufficient cash for fees")
        (nextCash, state.realizedPnl + gross - trade.fees - cost,
          holding.copy(quantity = holding.quantity - trade.quantity, costBasis = holding.costBasis - cost))
      }
      state.copy(cash = cash, realizedPnl = realized,
        holdings = (state.holdings.filterNot(_.ticker == trade.ticker) :+ updated).filter(_.quantity > 0).sortBy(_.ticker))
    }
}
