package io.greensheep.providers.jquants

import zio.*
import zio.json.EncoderOps

final class JquantsRateLimiter(state: Ref[JquantsRateLimitState]) {

  def snapshot: UIO[JquantsRateLimitState] = state.get

  def snapshotJson: UIO[String] = snapshot.map(_.toJsonPretty)

  def execute[R](fetch: ZIO[R, Throwable, JquantsHttpResponse]): ZIO[R, Throwable, JquantsHttpResponse] = {
    def loop: ZIO[R, Throwable, JquantsHttpResponse] =
      for {
        current <- state.get
        _ <- ZIO.sleep(current.intervalMs.millis)
        response <- fetch
        now = java.lang.System.currentTimeMillis()
        result <-
          if (response.status == 429) {
            val headers = RateLimitHeaders.from(response.headers, now)
            val stepped = JquantsRateLimit.step(current, ProbeEvent.TooManyRequests(headers), now)
            state.set(stepped.state) *>
              ZIO.logWarning(s"J-Quants HTTP 429; waiting ${stepped.waitMs}ms interval=${stepped.state.intervalMs}ms") *>
              ZIO.sleep(stepped.waitMs.millis) *>
              loop
          } else if (response.status >= 200 && response.status < 300) {
            val stepped = JquantsRateLimit.step(current, ProbeEvent.Success, now)
            state.set(stepped.state).as(response)
          } else ZIO.succeed(response)
      } yield result
    loop
  }
}

object JquantsRateLimiter {
  def probeLayer: ULayer[JquantsRateLimiter] =
    ZLayer.fromZIO(Ref.make(JquantsRateLimitState.probe()).map(JquantsRateLimiter(_)))

  def productionLayer: ULayer[JquantsRateLimiter] =
    ZLayer.fromZIO(Ref.make(JquantsRateLimitState.production()).map(JquantsRateLimiter(_)))
}
