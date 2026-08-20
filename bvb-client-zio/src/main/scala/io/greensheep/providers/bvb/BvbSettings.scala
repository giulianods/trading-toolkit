package io.greensheep.providers.bvb

object BvbSettings {
  val DatafeedBaseUrl: String = "https://wapi.bvb.ro"
  val SharesUrl: String = "https://www.bvb.ro/FinancialInstruments/Markets/Shares"
  val Referer: String = "https://www.bvb.ro/"
  val UserAgent: String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
  val DefaultMinIntervalMs: Long = 250
  val ProbeStartIntervalMs: Long = 50
  val HistoryCountbackCap: Int = 5000
}
