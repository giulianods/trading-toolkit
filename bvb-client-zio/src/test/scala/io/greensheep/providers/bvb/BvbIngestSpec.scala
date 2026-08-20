package io.greensheep.providers.bvb

import zio.*
import zio.http.{Client, DnsResolver, ZClient}
import zio.http.netty.NettyConfig
import zio.json.EncoderOps
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate

object BvbIngestSpec extends ZIOSpecDefault {

  private def repoRoot: Path = {
    var dir = Paths.get(java.lang.System.getProperty("user.dir"))
    while (!Files.isRegularFile(dir.resolve("build.sbt")) && dir.getParent != null) dir = dir.getParent
    dir
  }

  private val root: Path = repoRoot.resolve("data").resolve("bvb")
  private val quotesDir: Path = root.resolve("quotes")

  private val httpClient =
    (ZLayer.succeed(ZClient.Config.default.noIdleTimeout) ++
      ZLayer.succeed(NettyConfig.default) ++
      DnsResolver.default) >>> Client.live

  override def spec = suite("BVB one-time full ingest")(
    test("downloads every main-market share history into data/bvb") {
      ZIO.scoped {
        for {
          _ <- ZIO.attempt {
            Files.createDirectories(quotesDir)
          }
          client <- ZIO.service[BvbClient]
          limiter <- ZIO.service[BvbRateLimiter]
          shares <- client.listMainShares
          _ <- writeJson(root.resolve("shares.json"), shares.toJsonPretty)
          yesterday = LocalDate.now().minusDays(1)
          from = LocalDate.of(1997, 1, 1)
          errors <- Ref.make(Seq.empty[String])
          _ <- ZIO.foreachDiscard(shares) { instrument =>
            fetchUntilOk(client, instrument.ticker, from, yesterday, errors)
          }
          failed <- errors.get
          _ <- writeJson(root.resolve("errors.json"), failed.toJsonPretty)
          snapshot <- limiter.snapshotJson
          _ <- writeString(root.resolve("rate-limit.json"), snapshot)
          missing = shares.filterNot { instrument =>
            Files.isRegularFile(quotesDir.resolve(s"${instrument.ticker}.json"))
          }
        } yield assertTrue(
          shares.nonEmpty,
          shares.exists(_.ticker == "TLV"),
          missing.isEmpty,
          failed.isEmpty
        )
      }
    } @@ TestAspect.timeout(45.minutes)
  ).provide(httpClient ++ BvbClient.probeLayer) @@ TestAspect.withLiveClock @@ TestAspect.ignore

  private def fetchUntilOk(
      client: BvbClient,
      ticker: String,
      from: LocalDate,
      to: LocalDate,
      errors: Ref[Seq[String]]
  ): ZIO[Client, Throwable, Unit] = {
    def loop(attempt: Int): ZIO[Client, Throwable, Unit] =
      client.history(ticker, from, to).foldZIO(
        error =>
          if (attempt >= 8)
            errors.update(_ :+ s"$ticker: ${error.getMessage}").unit
          else
            ZIO.logWarning(s"Retry $ticker attempt $attempt: ${error.getMessage}") *>
              ZIO.sleep(math.min(60, 2 << attempt).seconds) *>
              loop(attempt + 1),
        bars =>
          writeJson(quotesDir.resolve(s"$ticker.json"), bars.toJsonPretty) *>
            errors.update(_.filterNot(_.startsWith(s"$ticker:")))
      )
    loop(1)
  }

  private def writeJson(path: Path, json: String): Task[Unit] = writeString(path, json)

  private def writeString(path: Path, value: String): Task[Unit] =
    ZIO.attempt {
      Files.createDirectories(path.getParent)
      Files.writeString(path, value, StandardCharsets.UTF_8)
    }
}
