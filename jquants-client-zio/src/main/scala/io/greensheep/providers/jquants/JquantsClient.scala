package io.greensheep.providers.jquants

import io.greensheep.providers.jquants.datatypes.*
import io.greensheep.trading.core.utils.LocalConfig
import zio.*
import zio.http.Client
import zio.json.*

import java.net.URLEncoder
import java.time.LocalDate

final class JquantsClient(limiter: JquantsRateLimiter, apiKey: String) {

  def listMainShares: ZIO[Client, Throwable, Seq[JquantsInstrument]] =
    paginateMaster(None, Seq.empty).map { rows =>
      rows.filter(JquantsUniverse.isMainBoardShare).map(JquantsUniverse.toInstrument).distinctBy(_.ticker)
    }.flatMap { instruments =>
      ZIO
        .fail(new NoSuchElementException("J-Quants master returned no Prime/Standard common shares"))
        .when(instruments.isEmpty)
        .as(instruments)
    }

  def history(code: String, from: LocalDate, to: LocalDate): ZIO[Client, Throwable, Seq[JquantsBar]] =
    if (to.isBefore(from)) ZIO.succeed(Seq.empty)
    else
      paginateBars(
        params = Map(
          "code" -> code,
          "from" -> from.toString,
          "to" -> to.toString
        ),
        paginationKey = None,
        acc = Seq.empty
      ).map(_.flatMap(DailyBarRow.toBar).sortBy(_.date))

  /** All bars for one calendar day (efficient for free-tier rate limits). */
  def barsForDate(day: LocalDate): ZIO[Client, Throwable, Seq[DailyBarRow]] =
    paginateBars(Map("date" -> day.toString), None, Seq.empty)

  def instrumentInfo(code: String): ZIO[Client, Throwable, JquantsInstrumentInfo] =
    for {
      rows <- paginateMaster(Some(code), Seq.empty)
      row <- ZIO
        .fromOption(rows.headOption)
        .orElseFail(new NoSuchElementException(s"J-Quants master missing code $code"))
      instrument = JquantsUniverse.toInstrument(row)
    } yield JquantsInstrumentInfo(
      ticker = instrument.ticker,
      name = instrument.name,
      description = instrument.name,
      code = instrument.code
    )

  private def paginateMaster(code: Option[String], acc: Seq[MasterRow], paginationKey: Option[String] = None)
      : ZIO[Client, Throwable, Seq[MasterRow]] = {
    val params = code.map(c => Map("code" -> c)).getOrElse(Map.empty) ++
      paginationKey.map(k => Map("pagination_key" -> k)).getOrElse(Map.empty)
    val raw = buildUrl("/equities/master", params)
    for {
      response <- limiter.execute(JquantsHttp.get(raw, apiKey))
      body <- JquantsHttp.requireOk(response)
      decoded <- ZIO.fromEither(body.fromJson[MasterResponse]).mapError(err =>
        new RuntimeException(s"J-Quants master decode failed: $err")
      )
      merged = acc ++ decoded.data.getOrElse(Nil)
      next <- decoded.pagination_key match {
        case Some(key) if key.nonEmpty => paginateMaster(code, merged, Some(key))
        case _                         => ZIO.succeed(merged)
      }
    } yield next
  }

  private def paginateBars(params: Map[String, String], paginationKey: Option[String], acc: Seq[DailyBarRow])
      : ZIO[Client, Throwable, Seq[DailyBarRow]] = {
    val withPage = params ++ paginationKey.map(k => Map("pagination_key" -> k)).getOrElse(Map.empty)
    val raw = buildUrl("/equities/bars/daily", withPage)
    for {
      response <- limiter.execute(JquantsHttp.get(raw, apiKey))
      body <- JquantsHttp.requireOk(response)
      decoded <- ZIO.fromEither(body.fromJson[DailyBarsResponse]).mapError(err =>
        new RuntimeException(s"J-Quants bars decode failed: $err")
      )
      merged = acc ++ decoded.data.getOrElse(Nil)
      next <- decoded.pagination_key match {
        case Some(key) if key.nonEmpty => paginateBars(params, Some(key), merged)
        case _                         => ZIO.succeed(merged)
      }
    } yield next
  }

  private def buildUrl(path: String, params: Map[String, String]): String = {
    val query = params
      .map { case (k, v) => s"$k=${URLEncoder.encode(v, "UTF-8")}" }
      .mkString("&")
    if (query.isEmpty) s"${JquantsSettings.ApiBaseUrl}$path"
    else s"${JquantsSettings.ApiBaseUrl}$path?$query"
  }
}

object JquantsClient {
  private val fromDeps: ZLayer[JquantsRateLimiter, Nothing, JquantsClient] =
    ZLayer.fromFunction { (limiter: JquantsRateLimiter) =>
      val key = LocalConfig.get("jquants.api.key").getOrElse("unused")
      new JquantsClient(limiter, key)
    }

  /** Test layer with an explicit API key (does not read local.properties). */
  def layerWithKey(apiKey: String): ZLayer[Any, Nothing, JquantsClient & JquantsRateLimiter] =
    JquantsRateLimiter.probeLayer >+> ZLayer.fromFunction((limiter: JquantsRateLimiter) => new JquantsClient(limiter, apiKey))

  val live: ZLayer[Any, Nothing, JquantsClient] =
    JquantsRateLimiter.productionLayer >>> fromDeps
}
