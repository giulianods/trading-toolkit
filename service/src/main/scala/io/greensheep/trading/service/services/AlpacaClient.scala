package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.{Markets, Quote}
import io.greensheep.trading.core.utils.{HttpStatusException, LocalConfig, TransientRetry}
import zio.*
import zio.http.{Client, Header, Headers, Method, Request, URL}
import zio.json.*

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate, LocalTime, ZoneId}

private[service] object AlpacaClient {
  val zone: ZoneId = ZoneId.of(Markets.UsAlpaca.timeZone)
  val closeBuffer: LocalTime = LocalTime.of(20, 30)
  val batchSize = 100

  final case class Asset(symbol: String, name: String, exchange: String)
  given JsonDecoder[Asset] = DeriveJsonDecoder.gen[Asset]

  final case class Bar(t: Instant, o: Double, h: Double, l: Double, c: Double, v: Long) {
    def quote: Quote =
      Quote(t.atZone(zone).toLocalDate, o, h, l, c, v.min(Int.MaxValue.toLong).toInt)
  }
  given JsonDecoder[Bar] = DeriveJsonDecoder.gen[Bar]

  final case class BarsPage(bars: Option[Map[String, Seq[Bar]]], @jsonField("next_page_token") next: Option[String])
  given JsonDecoder[BarsPage] = DeriveJsonDecoder.gen[BarsPage]

  def latestSessionCandidate(now: Instant): LocalDate = {
    val eastern = now.atZone(zone)
    if (eastern.toLocalTime.isBefore(closeBuffer)) eastern.toLocalDate.minusDays(1) else eastern.toLocalDate
  }

  def barsEnd(day: LocalDate, now: Instant): Instant = {
    val sessionEnd = day.plusDays(1).atStartOfDay(zone).toInstant.minusSeconds(1)
    val delayed = now.minusSeconds(16 * 60)
    if (sessionEnd.isBefore(delayed)) sessionEnd else delayed
  }

  private def get[A: JsonDecoder](base: String, params: Seq[(String, String)]): ZIO[Client, Throwable, A] = {
    val query = params.map { case (key, value) => s"$key=${URLEncoder.encode(value, UTF_8)}" }.mkString("&")
    val request = ZIO.scoped {
      for {
        url <- ZIO.fromEither(URL.decode(s"$base?$query"))
        client <- ZIO.service[Client]
        response <- client.request(Request(
          url = url,
          method = Method.GET,
          headers = Headers(
            Header.Custom("APCA-API-KEY-ID", LocalConfig.require("alpaca.api.key")),
            Header.Custom("APCA-API-SECRET-KEY", LocalConfig.require("alpaca.api.secret"))
          )
        ))
        _ <- ZIO.fail(HttpStatusException(response.status.code, "Alpaca request failed")).unless(response.status.isSuccess)
        body <- response.body.asString
      } yield body
    }
    request
      .retry(TransientRetry.http)
      .flatMap(body => ZIO.fromEither(body.fromJson[A]).mapError(message => new IllegalArgumentException(message)))
      .ensuring(ZIO.sleep(350.millis))
  }

  def assets: ZIO[Client, Throwable, Seq[Asset]] = {
    val host =
      if (LocalConfig.get("alpaca.paper").contains("false")) "api.alpaca.markets"
      else "paper-api.alpaca.markets"
    get[Seq[Asset]](s"https://$host/v2/assets", Seq("status" -> "active", "asset_class" -> "us_equity"))
      .map(_.filterNot(_.exchange == "OTC").distinctBy(_.symbol))
  }

  def bars(symbols: Seq[String], start: LocalDate, end: LocalDate, now: Instant, pageSize: Int = 10000)
      : ZIO[Client, Throwable, Map[String, Seq[Quote]]] = {
    val params = Seq(
      "symbols" -> symbols.mkString(","),
      "timeframe" -> "1Day",
      "feed" -> "sip",
      "adjustment" -> "split",
      "limit" -> pageSize.toString,
      "start" -> start.atStartOfDay(zone).toInstant.toString,
      "end" -> barsEnd(end, now).toString
    )

    def fetch(token: Option[String], accumulated: Map[String, Vector[Quote]])
        : ZIO[Client, Throwable, Map[String, Seq[Quote]]] =
      get[BarsPage]("https://data.alpaca.markets/v2/stocks/bars", params ++ token.map("page_token" -> _)).flatMap { page =>
        val merged = page.bars.getOrElse(Map.empty).foldLeft(accumulated) { case (all, (symbol, bars)) =>
          val quotes = bars.iterator.map(_.quote).filter(q => !q.day.isBefore(start) && !q.day.isAfter(end))
          all.updated(symbol, all.getOrElse(symbol, Vector.empty) ++ quotes)
        }
        page.next.filter(_.nonEmpty) match {
          case None => ZIO.succeed(merged)
          case Some(next) if token.contains(next) => ZIO.fail(new IllegalStateException("Alpaca repeated a bars page token"))
          case Some(next) => fetch(Some(next), merged)
        }
      }

    if (symbols.isEmpty || end.isBefore(start)) ZIO.succeed(Map.empty) else fetch(None, Map.empty)
  }
}
