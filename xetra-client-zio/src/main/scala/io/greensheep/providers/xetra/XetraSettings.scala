package io.greensheep.providers.xetra

object XetraSettings {
  val ApiBaseUrl: String = "https://api.boerse-frankfurt.de"
  val WebsiteUrl: String = "https://www.boerse-frankfurt.de/"
  val InstrumentsCsvUrl: String =
    "https://www.cashmarket.deutsche-boerse.com/resource/blob/1528/f6222f12d29ad7476918e22dbd5d6d64/data/t7-xetr-allTradableInstruments.csv"
  val Referer: String = WebsiteUrl
  val UserAgent: String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
  val MicXetra: String = "XETR"
  val DefaultMinIntervalMs: Long = 250
  val ProbeStartIntervalMs: Long = 50
  val HistoryPageSize: Int = 2000
  val SaltRefreshMs: Long = 60 * 60 * 1000

  /** T7 market segment codes for Scale on Xetra (exclude). */
  val ScaleSegments: Set[String] = Set("054", "055")

  val PrimeSegment: String = "045"
  val GeneralSegment: String = "046"
  val RegulatedSegment: String = "003"

  val AllowedGermanSegments: Set[String] = Set(PrimeSegment, GeneralSegment, RegulatedSegment)

  /** Last-resort tracing salt when the website bundle cannot be scraped. */
  val FallbackSalt: String = "af5a8d16eb5dc49f8a72b26fd9185475c7a"
}
