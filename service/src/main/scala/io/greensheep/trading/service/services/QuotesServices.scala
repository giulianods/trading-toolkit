package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.providers.massive.MassiveAsyncApi
import io.greensheep.trading.core.utils.{QuoteHygiene, QuotesValidation}
import io.greensheep.trading.dataservice.{MongoDataRepo, MassiveBridge}
import zio.*
import zio.prelude.ZValidation

import scala.jdk.CollectionConverters.*
import java.time.LocalDate

object QuotesServices {

  val SaveBatchSize = 10
  val QuoteLoadParallelism = 8
  val HygieneParallelism = 4
  val DefaultHistoryMonths = 23
  val DefaultHistoryDays = 600
  /** Cap Massive full-resets per hygiene pass so we don't starve the quotes catch-up queue. */
  val MaxApiResetsPerPass = 20
  /** If quote cursor is older than this many days, skip API resets entirely. */
  val ApiResetMaxQuoteLagDays = 3

  def datesBetweenInclusive(startDate: LocalDate, endDate: LocalDate): Seq[LocalDate] = {
    val today = LocalDate.now()
    if (endDate.isBefore(startDate)) Seq.empty
    else startDate.datesUntil(endDate.plusDays(1)).iterator().asScala.toSeq.filterNot(_.isAfter(today.minusDays(1)))
  }

  val TickersUsCommonStocks: ZIO[MassiveAsyncApi, Throwable, Seq[String]] =
    ZIO.service[MassiveAsyncApi].flatMap(_.getTickers.runCollect.map(_.map(_.ticker)))

  val DailyQuotesRetrieval: ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    for {
      _ <- ZIO.logInfo("Retrieving quotes from the last update through local yesterday")
      stockUniverse <- MongoDataRepo.getStockUniverse
      start = stockUniverse.lastUpdateQuotes.minusDays(1)
      end = LocalDate.now().minusDays(1)
      lastWritten <- getDailySnapshotService(start, end)
      _ <- lastWritten match {
        case Some(date) =>
          MongoDataRepo.saveStockUniverse(stockUniverse.copy(lastUpdateQuotes = date))
        case None =>
          ZIO.logWarning("No quote dates were written; leaving lastUpdateQuotes unchanged")
      }
    } yield ()

  def sanityCheck(ticker: String): ZIO[MongoDatabase, String, Unit] = ZIO.logSpan("sanity-check") {
    for {
      quotes <- MongoDataRepo.getPriceData(ticker).mapError(_.getMessage)
      _ <- QuotesValidation.validateAll(quotes) match {
        case ZValidation.Failure(_, errors) => ZIO.fail(errors.mkString(", "))
        case ZValidation.Success(_, _)      => ZIO.succeed(())
      }
    } yield ()
  }

  def getDailySnapshotService(
      start: LocalDate,
      end: LocalDate
  ): ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Option[LocalDate]] = ZIO.logSpan("get-daily-quotes") {
    for {
      remoteApi <- ZIO.service[MassiveAsyncApi]
      datesToRetrieve = datesBetweenInclusive(start, end)
      _ <- ZIO.logInfo(s"Getting daily snapshots from $start to $end (${datesToRetrieve.size} days)")
      lastWritten <- ZIO.foldLeft(datesToRetrieve)(Option.empty[LocalDate] -> Option.empty[LocalDate]) {
        case ((lastOk, firstFailure), date) =>
          fetchAndStore(remoteApi, date).foldZIO(
            error =>
              ZIO.logError(s"Failed grouped daily for $date: ${error.getMessage}") *>
                ZIO.succeed(stepQuoteCursor(lastOk, firstFailure, date, Left(error))),
            written =>
              ZIO.logInfo(s"No trading session for $date").when(!written && firstFailure.isEmpty) *>
                ZIO.succeed(stepQuoteCursor(lastOk, firstFailure, date, Right(written)))
          )
      }
    } yield lastWritten._1
  }

  /** Empty sessions do not advance lastOk; a fetch error freezes the cursor after that date. */
  def stepQuoteCursor(
      lastOk: Option[LocalDate],
      firstFailure: Option[LocalDate],
      date: LocalDate,
      outcome: Either[Throwable, Boolean]
  ): (Option[LocalDate], Option[LocalDate]) =
    outcome match {
      case Left(_) =>
        (lastOk, firstFailure.orElse(Some(date)))
      case Right(_) if firstFailure.isDefined =>
        (lastOk, firstFailure)
      case Right(true) =>
        (Some(date), None)
      case Right(false) =>
        (lastOk, None)
    }

  private def fetchAndStore(
      remoteApi: MassiveAsyncApi,
      date: LocalDate
  ): ZIO[MongoDatabase, Throwable, Boolean] =
    for {
      aggregates <- remoteApi.getGroupedDaily(date)
      _ <- ZIO.logInfo(s"Retrieved ${aggregates.size} records for date $date")
      _ <- ZIO.when(aggregates.nonEmpty) {
        val grouped = aggregates.map(agg => agg.T.getOrElse("?") -> MassiveBridge.aggregateToQuote(agg)).groupBy(_._1)
        ZIO.foreachDiscard(grouped.toSeq) { case (ticker, pairs) =>
          MongoDataRepo.updateDailyQuotes(ticker, pairs.map(_._2))
        }
      }
    } yield aggregates.nonEmpty

  def rectifySplits(start: LocalDate, end: LocalDate): ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    ZIO.logSpan("rectify-split-quotes") {
      val defaultHistory = LocalDate.now().minusDays(DefaultHistoryDays)
      for {
        remoteApi <- ZIO.service[MassiveAsyncApi]
        splits <- remoteApi.getSplits(start, end).map(_.map(_.ticker))
        reverseSplits <- remoteApi.getReverseSplits(start, end).map(_.map(_.ticker))
        _ <- ZIO.foreachDiscard((splits ++ reverseSplits).distinct) { ticker =>
          replaceQuotesFrom(ticker, defaultHistory)
            .tap(_ => ZIO.logInfo(s"Updated split data $ticker"))
            .catchAll(error => ZIO.logError(s"Failed split rectify for $ticker: ${error.getMessage}"))
        }
        universe <- MongoDataRepo.getStockUniverse
        _ <- MongoDataRepo.saveStockUniverse(universe.copy(lastUpdateSplits = LocalDate.now()))
      } yield ()
    }

  /** Nightly pass: truncate ticker-reuse gaps and reset jump / otherwise-broken series. */
  def nightlyHygiene: ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    ZIO.logSpan("quote-hygiene") {
      for {
        _ <- ZIO.logInfo("Running nightly quote hygiene")
        universe <- MongoDataRepo.getStockUniverse
        actions <- ZIO
          .foreachPar(universe.symbols) { ticker =>
            diagnoseTicker(ticker).map(action => ticker -> action)
          }
          .withParallelism(QuoteLoadParallelism)
        dirty = actions.filterNot(_._2 == QuoteHygiene.Ok)
        localOnly = dirty.collect { case (t, a: QuoteHygiene.TruncateAndRefetch) => t -> a }
        needApi = dirty.collect { case (t, QuoteHygiene.FullReset) => t }
        quoteLagDays =
          java.time.temporal.ChronoUnit.DAYS.between(universe.lastUpdateQuotes, LocalDate.now().minusDays(1))
        apiBudget =
          if (quoteLagDays > ApiResetMaxQuoteLagDays) 0
          else MaxApiResetsPerPass
        apiBatch = needApi.take(apiBudget)
        deferred = needApi.size - apiBatch.size
        _ <- ZIO.logInfo(
          s"Quote hygiene: ${dirty.size} dirty (${localOnly.size} local truncate, ${apiBatch.size} API reset, $deferred deferred; quoteLag=${quoteLagDays}d) of ${universe.symbols.size}"
        )
        _ <- ZIO.logWarning(
          s"Skipping ${needApi.size} Massive full-resets while quotes are ${quoteLagDays}d behind (lastUpdateQuotes=${universe.lastUpdateQuotes})"
        ).when(apiBudget == 0 && needApi.nonEmpty)
        // Gap truncates are local Mongo writes — do not burn the 16s Massive queue.
        _ <- ZIO.foreachParDiscard(localOnly) { case (ticker, action) =>
          applyHygiene(ticker, action)
            .catchAll(error => ZIO.logError(s"Hygiene failed for $ticker: ${error.getMessage}"))
        }.withParallelism(QuoteLoadParallelism)
        // Full resets share the rate-limited Massive queue; keep parallelism at 1 and cap count.
        _ <- ZIO.foreachDiscard(apiBatch) { ticker =>
          applyHygiene(ticker, QuoteHygiene.FullReset)
            .catchAll(error => ZIO.logError(s"Hygiene failed for $ticker: ${error.getMessage}"))
        }
      } yield ()
    }

  private def diagnoseTicker(ticker: String): ZIO[MongoDatabase, Throwable, QuoteHygiene.Action] =
    MongoDataRepo.getPriceData(ticker).foldZIO(
      _ => ZIO.succeed(QuoteHygiene.Ok),
      data => ZIO.succeed(QuoteHygiene.diagnose(data))
    )

  private def applyHygiene(
      ticker: String,
      action: QuoteHygiene.Action
  ): ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    action match {
      case QuoteHygiene.Ok =>
        ZIO.unit
      case QuoteHygiene.TruncateAndRefetch(from) =>
        ZIO.logInfo(s"Hygiene local truncate $ticker from $from") *>
          truncateQuotesLocally(ticker, from)
      case QuoteHygiene.FullReset =>
        ZIO.logInfo(s"Hygiene full reset $ticker") *>
          replaceQuotesFrom(ticker, LocalDate.now().minusMonths(DefaultHistoryMonths))
    }

  /** Drop pre-gap bars in Mongo only (ticker-reuse). Avoids Massive rate limit. */
  def truncateQuotesLocally(ticker: String, from: LocalDate): ZIO[MongoDatabase, Throwable, Unit] =
    for {
      data <- MongoDataRepo.getPriceData(ticker)
      kept = data.quotes.filter(q => !q.day.isBefore(from))
      _ <- MongoDataRepo.clearQuotes(ticker)
      _ <- MongoDataRepo.updateDailyQuotes(ticker, kept)
      _ <- ZIO.logInfo(s"Truncated quotes for $ticker from $from (${kept.size} bars kept)")
    } yield ()

  def replaceQuotesFrom(
      ticker: String,
      from: LocalDate
  ): ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    for {
      remoteApi <- ZIO.service[MassiveAsyncApi]
      aggregates <- remoteApi.getAggregates(ticker, from)
      quotes = aggregates.map(MassiveBridge.aggregateToQuote)
      _ <- MongoDataRepo.clearQuotes(ticker)
      _ <- MongoDataRepo.updateDailyQuotes(ticker, quotes)
      _ <- ZIO.logInfo(s"Replaced quotes for $ticker from $from (${quotes.size} bars)")
    } yield ()

  /** Rescreen immediately (quality gates), then repair quotes, then rescreen again. */
  def hygieneThenRescreen: ZIO[MongoDatabase & MassiveAsyncApi, Throwable, Unit] =
    for {
      _ <- ScreenerServices.ScreenerTask
      _ <- nightlyHygiene
      _ <- ScreenerServices.ScreenerTask
    } yield ()
}
