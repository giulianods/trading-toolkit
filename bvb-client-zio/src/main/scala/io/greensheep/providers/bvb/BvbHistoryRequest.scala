package io.greensheep.providers.bvb

import java.time.LocalDate
import java.time.ZoneId

object BvbHistoryRequest {

  val ResolutionDaily: String = "1D"
  val CurrencyRon: String = "RON"

  def countback(from: LocalDate, to: LocalDate): Int = {
    val days = java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt + 5
    math.max(1, math.min(BvbSettings.HistoryCountbackCap, days))
  }

  def unixSeconds(day: LocalDate, zone: ZoneId = ZoneId.of("Europe/Bucharest")): Long =
    day.atStartOfDay(zone).toEpochSecond

  def buildUrl(
      symbol: String,
      from: LocalDate,
      to: LocalDate,
      baseUrl: String = BvbSettings.DatafeedBaseUrl,
      adjusted: Boolean = true,
      currencyCode: String = CurrencyRon
  ): String = {
    val fromTs = unixSeconds(from)
    val toTs = unixSeconds(to.plusDays(1))
    val ajustFlag = if (adjusted) "1" else "0"
    s"$baseUrl/api/history?symbol=${java.net.URLEncoder.encode(symbol, "UTF-8")}&from=$fromTs&to=$toTs&rs=$ResolutionDaily&ajust=$ajustFlag&countback=${countback(from, to)}&currencyCode=$currencyCode"
  }

  def symbolInfoUrl(symbol: String, baseUrl: String = BvbSettings.DatafeedBaseUrl): String =
    s"$baseUrl/api/symbols?symbol=${java.net.URLEncoder.encode(symbol, "UTF-8")}"
}
