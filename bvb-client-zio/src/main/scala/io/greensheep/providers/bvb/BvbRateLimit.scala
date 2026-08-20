package io.greensheep.providers.bvb

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.util.Try

final case class RateLimitHeaders(
    retryAfterMs: Option[Long],
    limit: Option[Long],
    remaining: Option[Long],
    resetMs: Option[Long],
    raw: Map[String, String]
) {
  def isUsable: Boolean = retryAfterMs.isDefined || resetMs.isDefined || limit.isDefined

  def waitMs(nowMs: Long = System.currentTimeMillis()): Long = {
    val fromRetry = retryAfterMs.getOrElse(0L)
    val fromReset = resetMs.map(reset => math.max(0L, reset - nowMs)).getOrElse(0L)
    math.max(fromRetry, fromReset)
  }

  def minIntervalMs(nowMs: Long = System.currentTimeMillis()): Option[Long] = {
    val window = resetMs.map(reset => math.max(1L, reset - nowMs)).orElse(retryAfterMs)
    val fromLimit = for {
      lim <- limit
      win <- window
    } yield math.max(1L, win / math.max(lim, 1L))
    val candidates = Seq(fromLimit, retryAfterMs).flatten
    if (candidates.isEmpty) None else Some(candidates.max)
  }
}

object RateLimitHeaders {
  implicit val encoder: JsonEncoder[RateLimitHeaders] = DeriveJsonEncoder.gen[RateLimitHeaders]
  implicit val decoder: JsonDecoder[RateLimitHeaders] = DeriveJsonDecoder.gen[RateLimitHeaders]

  def from(headers: Map[String, String], nowMs: Long = System.currentTimeMillis()): RateLimitHeaders = {
    val normalized = headers.map { case (k, v) => k.toLowerCase -> v.trim }.filter(_._2.nonEmpty)
    val retryAfter = normalized.get("retry-after").flatMap(parseRetryAfter(_, nowMs))
    val limit = firstLong(normalized, "ratelimit-limit", "x-ratelimit-limit", "x-rate-limit-limit")
    val remaining = firstLong(normalized, "ratelimit-remaining", "x-ratelimit-remaining")
    val resetMs = firstString(normalized, "ratelimit-reset", "x-ratelimit-reset").flatMap(parseReset(_, nowMs))
    RateLimitHeaders(retryAfter, limit, remaining, resetMs, normalized)
  }

  private def firstString(headers: Map[String, String], names: String*): Option[String] =
    names.view.flatMap(headers.get).headOption

  private def firstLong(headers: Map[String, String], names: String*): Option[Long] =
    firstString(headers, names*).flatMap(parseLeadingLong)

  private def parseLeadingLong(value: String): Option[Long] =
    """\d+""".r.findFirstIn(value).flatMap(n => Try(n.toLong).toOption)

  def parseRetryAfter(value: String, nowMs: Long): Option[Long] =
    Try(value.trim.toLong).toOption.map(_ * 1000).orElse(parseHttpDate(value).map(ts => math.max(0L, ts - nowMs)))

  def parseReset(value: String, nowMs: Long): Option[Long] =
    parseLeadingLong(value).map { n =>
      if (n > 1_000_000_000L) {
        val ms = if (n > 1_000_000_000_000L) n else n * 1000
        ms
      } else nowMs + n * 1000
    }.orElse(parseHttpDate(value))

  private def parseHttpDate(value: String): Option[Long] = {
    val formats = Seq(
      DateTimeFormatter.RFC_1123_DATE_TIME,
      DateTimeFormatter.ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz")
    )
    formats.view
      .flatMap(fmt => Try(ZonedDateTime.parse(value, fmt.withZone(ZoneOffset.UTC))).toOption)
      .map(_.toInstant.toEpochMilli)
      .headOption
      .orElse(Try(Instant.parse(value).toEpochMilli).toOption)
  }
}

final case class BvbRateLimitState(
    intervalMs: Long,
    lastOkIntervalMs: Option[Long],
    headerless429Count: Int,
    frozen: Boolean,
    chosenMinIntervalMs: Long,
    probeSteps: Seq[String] = Seq.empty,
    observedHeaders: Option[RateLimitHeaders] = None
)

object BvbRateLimitState {
  implicit val encoder: JsonEncoder[BvbRateLimitState] = DeriveJsonEncoder.gen[BvbRateLimitState]
  implicit val decoder: JsonDecoder[BvbRateLimitState] = DeriveJsonDecoder.gen[BvbRateLimitState]

  def probe(startIntervalMs: Long = BvbSettings.ProbeStartIntervalMs): BvbRateLimitState =
    BvbRateLimitState(
      intervalMs = startIntervalMs,
      lastOkIntervalMs = None,
      headerless429Count = 0,
      frozen = false,
      chosenMinIntervalMs = startIntervalMs
    )

  def production(minIntervalMs: Long = BvbSettings.DefaultMinIntervalMs): BvbRateLimitState =
    BvbRateLimitState(
      intervalMs = minIntervalMs,
      lastOkIntervalMs = Some(minIntervalMs),
      headerless429Count = 0,
      frozen = true,
      chosenMinIntervalMs = minIntervalMs
    )
}

enum ProbeEvent:
  case Success
  case TooManyRequests(headers: RateLimitHeaders)

final case class ProbeStep(state: BvbRateLimitState, waitMs: Long, retrySame: Boolean)

object BvbRateLimit {
  val SpeedUpFloorMs: Long = 20
  val SlowDownFloorMs: Long = 200
  val WaitCapMs: Long = 60_000

  def step(state: BvbRateLimitState, event: ProbeEvent, nowMs: Long = System.currentTimeMillis()): ProbeStep =
    event match {
      case ProbeEvent.Success =>
        val lastOk = state.intervalMs
        val nextInterval =
          if (state.frozen) state.intervalMs
          else math.max(SpeedUpFloorMs, math.min((state.intervalMs * 0.7).toLong, lastOk))
        val next = state.copy(
          intervalMs = nextInterval,
          lastOkIntervalMs = Some(lastOk),
          chosenMinIntervalMs = nextInterval,
          probeSteps = state.probeSteps :+ s"2xx interval=${state.intervalMs}->$nextInterval"
        )
        ProbeStep(next, nextInterval, retrySame = false)
      case ProbeEvent.TooManyRequests(headers) if headers.isUsable =>
        val wait = math.max(SlowDownFloorMs, math.min(WaitCapMs, math.max(headers.waitMs(nowMs), 2_000L)))
        val interval = headers.minIntervalMs(nowMs).map(v => math.max(v, SlowDownFloorMs)).getOrElse(math.max(state.intervalMs * 2, SlowDownFloorMs))
        val next = state.copy(
          intervalMs = interval,
          frozen = true,
          chosenMinIntervalMs = interval,
          observedHeaders = Some(headers),
          probeSteps = state.probeSteps :+ s"429-headers wait=$wait interval=$interval"
        )
        ProbeStep(next, wait, retrySame = true)
      case ProbeEvent.TooManyRequests(_) =>
        val count = state.headerless429Count + 1
        val wait = math.min(WaitCapMs, math.max(2_000L, state.intervalMs * 2))
        val slowed = math.max(
          state.lastOkIntervalMs.map(ok => (ok * 1.5).toLong).getOrElse(0L),
          math.max(state.intervalMs * 2, SlowDownFloorMs)
        )
        val freeze = count >= 3
        val frozenInterval =
          if (freeze)
            math.max(SlowDownFloorMs, (state.lastOkIntervalMs.getOrElse(state.intervalMs) * 1.5).toLong)
          else slowed
        val next = state.copy(
          intervalMs = frozenInterval,
          headerless429Count = count,
          frozen = freeze || state.frozen,
          chosenMinIntervalMs = frozenInterval,
          probeSteps = state.probeSteps :+ s"429-bare count=$count wait=$wait interval=$frozenInterval frozen=$freeze"
        )
        ProbeStep(next, wait, retrySame = true)
    }
}
