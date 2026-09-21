package io.greensheep.providers.bvb

import io.greensheep.providers.bvb.datatypes.{BvbBar, BvbInstrument, BvbSymbolInfo, UdfHistory}
import zio.*
import zio.http.{Client, URL}
import zio.json.*
import zio.json.ast.Json

import java.time.LocalDate

final class BvbClient(limiter: BvbRateLimiter) {

  /** Regulated Main Market + AeRO common shares (merged, deduped by ticker). */
  def listMainShares: ZIO[Client, Throwable, Seq[BvbInstrument]] =
    for {
      url <- decode(BvbSettings.SharesUrl)
      mainResponse <- limiter.execute(BvbHttp.get(url))
      mainHtml <- BvbHttp.requireOk(mainResponse)
      main = SharesPageParser.parse(mainHtml)
      aeroFields = BvbSharesForm.aeroPostFields(mainHtml)
      aeroResponse <- limiter.execute(BvbHttp.postForm(url, aeroFields))
      aeroHtml <- BvbHttp.requireOk(aeroResponse)
      aero = SharesPageParser.parse(aeroHtml)
      instruments = (main ++ aero).groupBy(_.ticker).values.map(_.head).toSeq.sortBy(_.ticker)
      _ <- ZIO.logInfo(s"BVB universe: ${main.size} regulated + ${aero.size} AeRO => ${instruments.size} unique")
      _ <- ZIO
        .fail(new NoSuchElementException("BVB shares pages returned no instruments"))
        .when(instruments.isEmpty)
      _ <- ZIO
        .logWarning(s"BVB AeRO tab returned no instruments (regulated=${main.size})")
        .when(aero.isEmpty)
    } yield instruments

  def history(symbol: String, from: LocalDate, to: LocalDate): ZIO[Client, Throwable, Seq[BvbBar]] = {
    val raw = BvbHistoryRequest.buildUrl(symbol, from, to)
    for {
      url <- decode(raw)
      response <- limiter.execute(BvbHttp.get(url))
      body <- BvbHttp.requireOk(response)
      decoded <- ZIO.fromEither(body.fromJson[UdfHistory]).mapError(err => new RuntimeException(s"BVB history decode failed for $symbol: $err"))
      bars <- decoded.s match {
        case "ok" | "no_data" => ZIO.succeed(decoded.bars)
        case other => ZIO.fail(new RuntimeException(s"BVB history status '$other' for $symbol: ${body.take(200)}"))
      }
    } yield bars
  }

  def symbolInfo(symbol: String): ZIO[Client, Throwable, BvbSymbolInfo] = {
    val raw = BvbHistoryRequest.symbolInfoUrl(symbol)
    for {
      url <- decode(raw)
      response <- limiter.execute(BvbHttp.get(url))
      body <- BvbHttp.requireOk(response)
    } yield parseSymbolInfo(symbol, body)
  }

  private def parseSymbolInfo(symbol: String, body: String): BvbSymbolInfo = {
    val fields = body.fromJson[Json.Obj].toOption.map { obj =>
      obj.fields.toMap.flatMap { case (k, v) =>
        val text = v match {
          case Json.Str(s) => s
          case Json.Num(n) => n.toString
          case Json.Bool(b) => b.toString
          case _ => ""
        }
        if (text.isEmpty) None else Some(k -> text)
      }
    }.getOrElse(Map.empty)
    BvbSymbolInfo(
      ticker = fields.getOrElse("ticker", fields.getOrElse("name", symbol)),
      name = fields.getOrElse("name", symbol),
      description = fields.getOrElse("description", fields.getOrElse("name", symbol)),
      timezone = fields.getOrElse("timezone", "Europe/Bucharest"),
      currencyCode = fields.getOrElse("currency_code", "RON")
    )
  }

  private def decode(raw: String): IO[Throwable, URL] =
    ZIO.fromEither(URL.decode(raw)).mapError(err => new RuntimeException(s"Invalid BVB URL '$raw': $err"))
}

object BvbClient {
  private val fromLimiter: ZLayer[BvbRateLimiter, Nothing, BvbClient] =
    ZLayer.fromFunction(BvbClient(_))

  val probeLayer: ZLayer[Any, Nothing, BvbClient & BvbRateLimiter] =
    BvbRateLimiter.probeLayer >+> fromLimiter

  val live: ZLayer[Any, Nothing, BvbClient] =
    BvbRateLimiter.productionLayer >>> fromLimiter
}
