package io.greensheep.providers.jquants

import io.greensheep.trading.core.utils.LocalConfig

object JquantsSettings {
  val ApiBaseUrl: String = "https://api.jquants.com/v2"
  val UserAgent: String = "trading-toolkit/jquants"
  /** Free plan ≈ 5 req/min; paid plans can lower via config. */
  val DefaultMinIntervalMs: Long = LocalConfig.get("jquants.min.interval.ms").flatMap(_.toLongOption).getOrElse(12_500L)
  val ProbeStartIntervalMs: Long = 2_000L
  /** Free plan delivers data ~12 weeks delayed; Light+ is ~0. */
  val DataDelayDays: Long = LocalConfig.get("jquants.data.delay.days").flatMap(_.toLongOption).getOrElse(84L)

  val PrimeSegment: String = "0111"
  val StandardSegment: String = "0112"
  val AllowedSegments: Set[String] = Set(PrimeSegment, StandardSegment)
  /** Common stock product category. */
  val CommonStockProduct: String = "011"

  def apiKey: String = LocalConfig.require("jquants.api.key")
}
