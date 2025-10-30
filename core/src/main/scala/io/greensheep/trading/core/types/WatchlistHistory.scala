package io.greensheep.trading.core.types

import zio.json.{DeriveJsonEncoder, JsonEncoder}

import java.time.LocalDate

final case class WatchlistHistoryEntry(day: LocalDate, watchlists: Seq[WatchlistSummary])

object WatchlistHistoryEntry {
  implicit val encoder: JsonEncoder[WatchlistHistoryEntry] = DeriveJsonEncoder.gen[WatchlistHistoryEntry]
}

final case class WatchlistSummary(name: String, symbolCount: Int)

object WatchlistSummary {
  implicit val encoder: JsonEncoder[WatchlistSummary] = DeriveJsonEncoder.gen[WatchlistSummary]
}
