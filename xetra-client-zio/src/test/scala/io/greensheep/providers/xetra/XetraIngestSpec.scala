package io.greensheep.providers.xetra

import io.greensheep.providers.xetra.datatypes.XetraBar
import zio.*
import zio.http.{Client, DnsResolver, ZClient}
import zio.http.netty.NettyConfig
import zio.json.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate

object XetraIngestSpec extends ZIOSpecDefault {

  private def repoRoot: Path = {
    var dir = Paths.get(java.lang.System.getProperty("user.dir"))
    while (!Files.isRegularFile(dir.resolve("build.sbt")) && dir.getParent != null) dir = dir.getParent
    dir
  }

  private val root: Path = repoRoot.resolve("data").resolve("xetra")
  private val quotesDir: Path = root.resolve("quotes")

  private val httpClient =
    (ZLayer.succeed(ZClient.Config.default.noIdleTimeout) ++
      ZLayer.succeed(NettyConfig.default) ++
      DnsResolver.default) >>> Client.live

  override def spec = suite("Xetra one-time full ingest")(
    test("downloads every main-market share history into data/xetra") {
      ZIO.scoped {
        for {
          _ <- ZIO.attempt(Files.createDirectories(quotesDir))
          client <- ZIO.service[XetraClient]
          limiter <- ZIO.service[XetraRateLimiter]
          shares <- client.listMainShares
          _ <- writeJson(root.resolve("shares.json"), shares.toJsonPretty)
          yesterday = LocalDate.now().minusDays(1)
          from = LocalDate.of(1997, 1, 1)
          errors <- Ref.make(Seq.empty[String])
          _ <- ZIO.foreachDiscard(shares) { instrument =>
            val existing = quotesDir.resolve(s"${instrument.ticker}.json")
            val alreadyGood = Files.isRegularFile(existing) && Files.size(existing) > 8
            if (alreadyGood) ZIO.unit
            else fetchUntilOk(client, instrument.ticker, instrument.isin, from, yesterday, errors)
          }
          failed <- errors.get
          _ <- writeJson(root.resolve("errors.json"), failed.toJsonPretty)
          snapshot <- limiter.snapshotJson
          _ <- writeString(root.resolve("rate-limit.json"), snapshot)
          missing = shares.filterNot { instrument =>
            Files.isRegularFile(quotesDir.resolve(s"${instrument.ticker}.json"))
          }
          sapBars = Option.when(Files.isRegularFile(quotesDir.resolve("SAP.json"))) {
            Files.readString(quotesDir.resolve("SAP.json")).fromJson[Seq[XetraBar]].toOption.map(_.size).getOrElse(0)
          }.getOrElse(0)
        } yield assertTrue(
          shares.nonEmpty,
          shares.size >= 200,
          shares.exists(_.ticker == "SAP"),
          missing.isEmpty,
          failed.isEmpty,
          sapBars > 100
        )
      }
    } @@ TestAspect.timeout(4.hours)
  ).provide(httpClient ++ XetraClient.probeLayer) @@ TestAspect.withLiveClock @@ TestAspect.ignore

  private def fetchUntilOk(
      client: XetraClient,
      ticker: String,
      isin: String,
      from: LocalDate,
      to: LocalDate,
      errors: Ref[Seq[String]]
  ): ZIO[Client, Throwable, Unit] = {
    def loop(attempt: Int): ZIO[Client, Throwable, Unit] =
      client.history(isin, from, to).foldZIO(
        error =>
          if (attempt >= 3)
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
