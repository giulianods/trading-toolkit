package io.greensheep.providers.xetra

import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object XetraHistoryRequest {

  def buildUrl(
      isin: String,
      from: LocalDate,
      to: LocalDate,
      offset: Int = 0,
      limit: Int = XetraSettings.HistoryPageSize,
      mic: String = XetraSettings.MicXetra,
      cleanSplit: Boolean = true
  ): String = {
    val params = Seq(
      "isin" -> isin,
      "mic" -> mic,
      "minDate" -> from.toString,
      "maxDate" -> to.toString,
      "limit" -> limit.toString,
      "offset" -> offset.toString,
      "cleanSplit" -> cleanSplit.toString,
      "cleanPayout" -> "false",
      "cleanSubscription" -> "false"
    ).map { case (k, v) => s"$k=${URLEncoder.encode(v, "UTF-8")}" }.mkString("&")
    s"${XetraSettings.ApiBaseUrl}/v1/data/price_history?$params"
  }

  def pageLimit(from: LocalDate, to: LocalDate): Int =
    math.max(1, math.min(XetraSettings.HistoryPageSize, ChronoUnit.DAYS.between(from, to).toInt + 5))
}
