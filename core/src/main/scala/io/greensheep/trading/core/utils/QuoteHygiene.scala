package io.greensheep.trading.core.utils

import io.greensheep.trading.core.types.{DailyQuoteData, Quote}

import java.time.LocalDate

/** Detects contaminated US quote series (ticker reuse / unadjusted splits). */
object QuoteHygiene {

  /** Same threshold as [[QuotesValidation]] missing-date check. */
  val MaxGapDays: Int = 7

  /** Day-over-day close ratio that usually means an unadjusted reverse/forward split. */
  val JumpRatio: Double = 4.0

  sealed trait Action
  case object Ok extends Action
  /** Drop bars before this date and refetch adjusted aggs from it (ticker reuse / long halt). */
  final case class TruncateAndRefetch(from: LocalDate) extends Action
  /** Clear and refetch ~23 months of adjusted history (jumps / other validation faults). */
  case object FullReset extends Action

  /** Last calendar day that starts a new continuous series after a gap > MaxGapDays. */
  def lastSeriesStartAfterGap(quotes: Seq[Quote], maxGapDays: Int = MaxGapDays): Option[LocalDate] = {
    val ordered = quotes.sortBy(_.day.toEpochDay)
    ordered
      .sliding(2)
      .collect {
        case Seq(a, b) if a.day.plusDays(maxGapDays).isBefore(b.day) => b.day
      }
      .toSeq
      .lastOption
  }

  def hasCloseJump(quotes: Seq[Quote], jumpRatio: Double = JumpRatio): Boolean = {
    val ordered = quotes.sortBy(_.day.toEpochDay).filter(q => q.c > 0)
    ordered.sliding(2).exists {
      case Seq(a, b) =>
        val ratio = math.max(a.c, b.c) / math.min(a.c, b.c)
        ratio >= jumpRatio
      case _ => false
    }
  }

  def diagnose(data: DailyQuoteData): Action = {
    val quotes = data.quotes
    if (quotes.isEmpty) Ok
    else {
      lastSeriesStartAfterGap(quotes) match {
        case Some(from) => TruncateAndRefetch(from)
        case None if hasCloseJump(quotes) => FullReset
        case None =>
          QuotesValidation.validateAll(data) match {
            case zio.prelude.Validation.Failure(_, _) => FullReset
            case zio.prelude.Validation.Success(_, _) => Ok
          }
      }
    }
  }
}
