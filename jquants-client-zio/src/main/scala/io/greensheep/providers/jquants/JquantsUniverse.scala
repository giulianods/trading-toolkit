package io.greensheep.providers.jquants

import io.greensheep.providers.jquants.datatypes.{DailyBarRow, JquantsBar, JquantsInstrument, MasterRow}

object JquantsUniverse {

  /** Prefer 4-digit common-stock ticker when J-Quants returns a 5-digit code ending in 0. */
  def tickerFromCode(code: String): String = {
    val trimmed = code.trim
    if (trimmed.length == 5 && trimmed.endsWith("0")) trimmed.substring(0, 4)
    else trimmed
  }

  def isMainBoardShare(row: MasterRow): Boolean = {
    val segment = row.Mkt.getOrElse("")
    val product = row.ProdCat.getOrElse("")
    JquantsSettings.AllowedSegments.contains(segment) &&
      product == JquantsSettings.CommonStockProduct &&
      row.Code.nonEmpty
  }

  def toInstrument(row: MasterRow): JquantsInstrument = {
    val code = row.Code.trim
    val ticker = tickerFromCode(code)
    val name = row.CoNameEn.filter(_.nonEmpty).orElse(row.CoName).getOrElse(ticker)
    JquantsInstrument(ticker, code, name, row.Mkt.getOrElse(""))
  }

  def barsByTicker(rows: Seq[DailyBarRow]): Map[String, Seq[JquantsBar]] =
    rows
      .flatMap { row =>
        DailyBarRow.toBar(row).map(bar => tickerFromCode(row.Code) -> bar)
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.sortBy(_.date))
      .toMap
}
