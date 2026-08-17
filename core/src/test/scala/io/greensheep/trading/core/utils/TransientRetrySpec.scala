package io.greensheep.trading.core.utils

import zio.test.*

object TransientRetrySpec extends ZIOSpecDefault {

  override def spec = suite("TransientRetry")(
    test("retries timeouts and 429/5xx") {
      assertTrue(
        TransientRetry.isTransient(HttpStatusException(429, "slow down")),
        TransientRetry.isTransient(HttpStatusException(503, "unavailable")),
        TransientRetry.isTransient(new java.net.SocketTimeoutException("timed out"))
      )
    },
    test("does not retry 403 today or decode failures") {
      assertTrue(
        TransientRetry.isForbiddenToday(HttpStatusException(403, "forbidden")),
        !TransientRetry.isTransient(HttpStatusException(403, "PLAN doesn't include this data")),
        !TransientRetry.isTransient(new RuntimeException("Failed to decode Finnhub response")),
        !TransientRetry.isTransient(HttpStatusException(400, "bad request"))
      )
    }
  )
}
