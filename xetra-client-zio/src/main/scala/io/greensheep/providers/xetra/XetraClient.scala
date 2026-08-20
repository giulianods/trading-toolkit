package io.greensheep.providers.xetra

import io.greensheep.providers.xetra.datatypes.{PriceHistoryResponse, XetraBar, XetraInstrument, XetraInstrumentInfo}
import zio.*
import zio.http.Client
import zio.json.*
import zio.json.ast.Json

import java.time.LocalDate

final class XetraClient(http: XetraHttp, limiter: XetraRateLimiter) {

  def listMainShares: ZIO[Client, Throwable, Seq[XetraInstrument]] =
    for {
      response <- limiter.execute(http.getPublic(XetraSettings.InstrumentsCsvUrl))
      csv <- XetraHttp.requireOk(response)
      instruments = XetraCsvParser.parse(csv)
      _ <- ZIO.fail(new NoSuchElementException("Xetra instrument CSV returned no German shares")).when(instruments.isEmpty)
    } yield instruments

  def history(isin: String, from: LocalDate, to: LocalDate): ZIO[Client, Throwable, Seq[XetraBar]] =
    if (to.isBefore(from)) ZIO.succeed(Seq.empty)
    else {
      val windows = historyWindows(from, to)
      ZIO.foldLeft(windows)(Seq.empty[XetraBar]) { (acc, window) =>
        fetchWindow(isin, window._1, window._2).map(bars => acc ++ bars)
      }.map(_.sortBy(_.date).distinctBy(_.date))
    }

  private def historyWindows(from: LocalDate, to: LocalDate): Seq[(LocalDate, LocalDate)] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[(LocalDate, LocalDate)]
    var start = from
    while (!start.isAfter(to)) {
      val end = start.plusYears(5).minusDays(1)
      buf += ((start, if (end.isAfter(to)) to else end))
      start = end.plusDays(1)
    }
    buf.toSeq
  }

  private def fetchWindow(isin: String, from: LocalDate, to: LocalDate): ZIO[Client, Throwable, Seq[XetraBar]] = {
    def page(offset: Int, acc: Seq[XetraBar]): ZIO[Client, Throwable, Seq[XetraBar]] = {
      val raw = XetraHistoryRequest.buildUrl(
        isin,
        from,
        to,
        offset = offset,
        limit = XetraHistoryRequest.pageLimit(from, to)
      )
      for {
        response <- limiter.execute(http.get(raw))
        body <- XetraHttp.requireOk(response)
        decoded <- ZIO.fromEither(XetraJson.sanitize(body).fromJson[PriceHistoryResponse]).mapError(err =>
          new RuntimeException(s"Xetra history decode failed for $isin: $err")
        )
        merged = acc ++ decoded.bars
        total = decoded.totalCount.getOrElse(merged.size)
        next <-
          if (decoded.bars.isEmpty || merged.size >= total) ZIO.succeed(merged)
          else page(offset + decoded.bars.size, merged)
      } yield next
    }
    page(0, Seq.empty)
  }

  def instrumentInfo(isin: String): ZIO[Client, Throwable, XetraInstrumentInfo] = {
    val raw = s"${XetraSettings.ApiBaseUrl}/v1/data/data_sheet_header?isin=${java.net.URLEncoder.encode(isin, "UTF-8")}"
    for {
      response <- limiter.execute(http.get(raw))
      body <- XetraHttp.requireOk(response)
    } yield parseInstrumentInfo(isin, body)
  }

  private def parseInstrumentInfo(isin: String, body: String): XetraInstrumentInfo = {
    val fields = body.fromJson[Json.Obj].toOption.map(_.fields.toMap).getOrElse(Map.empty)
    def text(key: String): Option[String] =
      fields.get(key).flatMap {
        case Json.Str(value) => Some(value)
        case Json.Obj(nested) =>
          nested.toMap.get("originalValue").collect { case Json.Str(value) => value }
        case _ => None
      }
    val name = text("instrumentName").getOrElse(isin)
    XetraInstrumentInfo(
      ticker = text("exchangeSymbol").getOrElse(isin),
      name = name,
      description = name,
      isin = text("isin").getOrElse(isin),
      wkn = text("wkn").getOrElse("")
    )
  }
}

object XetraClient {
  private val fromDeps: ZLayer[XetraHttp & XetraRateLimiter, Nothing, XetraClient] =
    ZLayer.fromFunction(new XetraClient(_, _))

  val probeLayer: ZLayer[Any, Nothing, XetraClient & XetraRateLimiter & XetraHttp] =
    XetraHttp.layer ++ XetraRateLimiter.probeLayer >+> fromDeps

  val live: ZLayer[Any, Nothing, XetraClient] =
    XetraHttp.layer ++ XetraRateLimiter.productionLayer >>> fromDeps
}
