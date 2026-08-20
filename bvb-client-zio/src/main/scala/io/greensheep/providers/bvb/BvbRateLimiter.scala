package io.greensheep.providers.bvb

import zio.*
import zio.json.EncoderOps

final class BvbRateLimiter(state: Ref[BvbRateLimitState]) {

  def snapshot: UIO[BvbRateLimitState] = state.get

  def snapshotJson: UIO[String] = snapshot.map(_.toJsonPretty)

  def execute[R](fetch: ZIO[R, Throwable, BvbHttpResponse]): ZIO[R, Throwable, BvbHttpResponse] = {
    def loop: ZIO[R, Throwable, BvbHttpResponse] =
      for {
        current <- state.get
        _ <- ZIO.sleep(current.intervalMs.millis)
        response <- fetch
        now = java.lang.System.currentTimeMillis()
        result <-
          if (response.status == 429) {
            val headers = RateLimitHeaders.from(response.headers, now)
            val stepped = BvbRateLimit.step(current, ProbeEvent.TooManyRequests(headers), now)
            state.set(stepped.state) *>
              ZIO.logWarning(s"BVB HTTP 429; waiting ${stepped.waitMs}ms interval=${stepped.state.intervalMs}ms") *>
              ZIO.sleep(stepped.waitMs.millis) *>
              loop
          } else if (response.status >= 200 && response.status < 300) {
            val stepped = BvbRateLimit.step(current, ProbeEvent.Success, now)
            state.set(stepped.state).as(response)
          } else ZIO.succeed(response)
      } yield result
    loop
  }
}

object BvbRateLimiter {
  def probeLayer: ULayer[BvbRateLimiter] =
    ZLayer.fromZIO(Ref.make(BvbRateLimitState.probe()).map(BvbRateLimiter(_)))

  def productionLayer: ULayer[BvbRateLimiter] =
    ZLayer.fromZIO(Ref.make(BvbRateLimitState.production()).map(BvbRateLimiter(_)))
}
