package io.greensheep.providers.eodhd

import io.greensheep.trading.core.types.Quote
import io.greensheep.trading.core.utils.{HttpStatusException, LocalConfig, TransientRetry}
import zio.*
import zio.http.{Client, QueryParams, Request, URL}
import zio.json.*

import java.time.LocalDate

final class EodhdClient private (requestGate: Semaphore) {
  import EodhdClient.*

  def canadianShares: ZIO[Client, Throwable, Seq[EodhdShare]] =
    ZIO.foreach(exchanges) { exchange =>
      get[Seq[Listing]]("exchange-symbol-list", exchange, "type" -> "common_stock").flatMap { listings =>
        val shares = commonShares(exchange, listings)
        if (shares.nonEmpty) ZIO.succeed(shares)
        else ZIO.fail(new IllegalStateException(s"No CAD common stocks returned for EODHD exchange $exchange"))
      }
    }.map(_.flatten)

  def history(ticker: String, from: LocalDate, to: LocalDate): ZIO[Client, Throwable, Seq[Quote]] = {
    val dates = Seq("from" -> from.toString, "to" -> to.toString)
    get[Seq[Bar]]("eod", ticker, (dates :+ ("period" -> "d"))*)
      .catchSome { case HttpStatusException(404, _) => ZIO.succeed(Seq.empty) }
      .flatMap { rows =>
        val bars = rows.filter(row => !row.date.isBefore(from) && !row.date.isAfter(to))
        if (bars.isEmpty) ZIO.succeed(Seq.empty)
        else get[Seq[Split]]("splits", ticker, dates*).flatMap { splits =>
          ZIO.fromEither(toQuotes(bars, splits.filter(split => !split.date.isBefore(from) && !split.date.isAfter(to))))
            .orElseFail(new IllegalStateException(s"Invalid EODHD split ratio for $ticker"))
        }
      }
  }

  private def get[A: JsonDecoder](endpoint: String, symbol: String, params: (String, String)*)
      : ZIO[Client, Throwable, A] = {
    val target = s"$endpoint/$symbol"
    val fetch = ZIO.scoped {
      for {
        key <- ZIO.attempt(LocalConfig.require("eodhd.api.key"))
        base <- ZIO.fromEither(URL.decode("https://eodhd.com/api"))
        url = (base / endpoint / symbol).addQueryParams(QueryParams(
          "api_token" -> key, (params :+ ("fmt" -> "json"))*
        ))
        client <- ZIO.service[Client]
        // Space request starts even when multiple symbols are fetched concurrently.
        _ <- requestGate.withPermit(ZIO.sleep(100.millis))
        response <- client.request(Request.get(url))
        body <- if (response.status.isSuccess) response.body.asString
          else ZIO.fail(HttpStatusException(response.status.code, s"EODHD $target request failed"))
      } yield body
    }
    fetch.retry(TransientRetry.http).mapError {
      case status: HttpStatusException => status
      case _ => new IllegalStateException(s"EODHD $target request could not be completed")
    }.flatMap(body => ZIO.fromEither(body.fromJson[A])
      .orElseFail(new IllegalStateException(s"Invalid EODHD response for $target")))
  }
}

final case class EodhdShare(ticker: String, name: String, exchange: String, isin: Option[String])

object EodhdClient {
  val live: ULayer[EodhdClient] = ZLayer.fromZIO(Semaphore.make(1).map(new EodhdClient(_)))

  private val exchanges = Seq("TO", "V")
  private val tickerCode = "[A-Za-z0-9][A-Za-z0-9.-]*".r

  private[eodhd] final case class Listing(
      @jsonField("Code") code: String,
      @jsonField("Name") name: String,
      @jsonField("Currency") currency: String,
      @jsonField("Type") kind: String,
      @jsonField("Isin") isin: Option[String]
  ) derives JsonDecoder

  private[eodhd] final case class Bar(
      date: LocalDate,
      open: Option[Double],
      high: Option[Double],
      low: Option[Double],
      close: Option[Double],
      volume: Option[Long]
  ) derives JsonDecoder

  private[eodhd] final case class Split(date: LocalDate, split: String) derives JsonDecoder

  private[eodhd] def commonShares(exchange: String, rows: Seq[Listing]): Seq[EodhdShare] =
    rows.collect {
      case row if row.currency == "CAD" && row.kind == "Common Stock" && tickerCode.matches(row.code) =>
        EodhdShare(s"${row.code}.$exchange", row.name, exchange, row.isin)
    }.distinctBy(_.ticker).sortBy(_.ticker)

  private[eodhd] def toQuotes(bars: Seq[Bar], splits: Seq[Split]): Either[String, Seq[Quote]] =
    splits.distinct.foldLeft[Either[String, Seq[(LocalDate, Double)]]](Right(Seq.empty)) { (parsed, event) =>
      for {
        factors <- parsed
        ratio <- splitRatio(event.split).toRight(event.split)
      } yield factors :+ (event.date -> ratio)
    }.map { factors =>
      bars.flatMap { bar =>
        val divisor = factors.iterator.filter(_._1.isAfter(bar.date)).map(_._2).product
        for {
          open <- bar.open.map(_ / divisor).filter(price => price.isFinite && price > 0)
          high <- bar.high.map(_ / divisor).filter(price => price.isFinite && price > 0)
          low <- bar.low.map(_ / divisor).filter(price => price.isFinite && price > 0)
          close <- bar.close.map(_ / divisor).filter(price => price.isFinite && price > 0)
          volume <- bar.volume.filter(_ >= 0)
          if low <= math.min(open, close) && high >= math.max(open, close)
          // EODHD already adjusts volume for splits.
        } yield Quote(bar.date, open, high, low, close, volume.min(Int.MaxValue.toLong).toInt)
      }.sortBy(_.day)
    }

  private def splitRatio(raw: String): Option[Double] =
    raw.split("/", -1) match {
      case Array(newShares, oldShares) =>
        for {
          numerator <- newShares.trim.toDoubleOption.filter(_ > 0)
          denominator <- oldShares.trim.toDoubleOption.filter(_ > 0)
          ratio = numerator / denominator
          if ratio.isFinite && ratio > 0
        } yield ratio
      case _ => None
    }
}
