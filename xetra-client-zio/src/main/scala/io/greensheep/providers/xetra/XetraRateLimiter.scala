package io.greensheep.providers.xetra

import zio.*
import zio.json.EncoderOps

final class XetraRateLimiter(state: Ref[XetraRateLimitState]) {

  def snapshot: UIO[XetraRateLimitState] = state.get

  def snapshotJson: UIO[String] = snapshot.map(_.toJsonPretty)

  def execute[R](fetch: ZIO[R, Throwable, XetraHttpResponse]): ZIO[R, Throwable, XetraHttpResponse] = {
    def loop: ZIO[R, Throwable, XetraHttpResponse] =
      for {
        current <- state.get
        _ <- ZIO.sleep(current.intervalMs.millis)
        response <- fetch
        now = java.lang.System.currentTimeMillis()
        result <-
          if (response.status == 429) {
            val headers = RateLimitHeaders.from(response.headers, now)
            val stepped = XetraRateLimit.step(current, ProbeEvent.TooManyRequests(headers), now)
            state.set(stepped.state) *>
              ZIO.logWarning(s"Xetra HTTP 429; waiting ${stepped.waitMs}ms interval=${stepped.state.intervalMs}ms") *>
              ZIO.sleep(stepped.waitMs.millis) *>
              loop
          } else if (response.status >= 200 && response.status < 300) {
            val stepped = XetraRateLimit.step(current, ProbeEvent.Success, now)
            state.set(stepped.state).as(response)
          } else ZIO.succeed(response)
      } yield result
    loop
  }
}

object XetraRateLimiter {
  def probeLayer: ULayer[XetraRateLimiter] =
    ZLayer.fromZIO(Ref.make(XetraRateLimitState.probe()).map(XetraRateLimiter(_)))

  def productionLayer: ULayer[XetraRateLimiter] =
    ZLayer.fromZIO(Ref.make(XetraRateLimitState.production()).map(XetraRateLimiter(_)))
}
