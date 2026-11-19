package io.finnhub.client

import io.greensheep.trading.core.utils.HttpStatusException
import zio.*

/** One shared gate for listings, financials, redirects and retries across all markets. */
final class FinnhubRateLimiter private (gate: Semaphore) {
  def run[R, A](request: ZIO[R, Throwable, A]): ZIO[R, Throwable, A] =
    gate.withPermit(request.tapError {
      case HttpStatusException(429, _) =>
        ZIO.logWarning("Finnhub HTTP 429: pausing the shared request gate for 60 seconds") *> ZIO.sleep(60.seconds)
      case _ => ZIO.unit
    }.ensuring(ZIO.sleep(1100.millis)))
}

object FinnhubRateLimiter {
  def make: UIO[FinnhubRateLimiter] = Semaphore.make(1).map(new FinnhubRateLimiter(_))
}
