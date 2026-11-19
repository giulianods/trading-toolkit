package io.finnhub.client

import io.greensheep.trading.core.utils.HttpStatusException
import zio.*
import zio.test.*

object FinnhubRateLimiterSpec extends ZIOSpecDefault {
  override def spec = suite("Finnhub shared rate limiter")(
    test("serializes concurrent callers with at least 1.1 seconds between requests") {
      for {
        limiter <- FinnhubRateLimiter.make
        starts <- Ref.make(Vector.empty[java.time.Instant])
        fiber <- ZIO.foreachParDiscard(1 to 4)(_ => limiter.run(Clock.instant.flatMap(t => starts.update(_ :+ t)))).fork
        _ <- TestClock.adjust(5.seconds)
        _ <- fiber.join
        times <- starts.get
      } yield assertTrue(times.size == 4, times.sliding(2).forall(pair =>
        java.time.Duration.between(pair.head, pair.last).toMillis >= 1100))
    },
    test("holds the shared gate for a minute after HTTP 429") {
      for {
        limiter <- FinnhubRateLimiter.make
        entered <- Promise.make[Nothing, Unit]
        first <- limiter.run(entered.succeed(()) *> ZIO.fail(HttpStatusException(429, "rate limit"))).exit.fork
        _ <- entered.await
        started <- Ref.make(false)
        second <- limiter.run(started.set(true)).fork
        _ <- TestClock.adjust(60.seconds)
        waiting <- started.get
        _ <- TestClock.adjust(3.seconds)
        result <- first.join
        _ <- second.join
      } yield assertTrue(!waiting, result.isFailure)
    }
  )
}
