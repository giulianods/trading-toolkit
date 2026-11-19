package io.greensheep.trading.service.services

import io.greensheep.trading.core.types.{IncomePeriod, IncomeStatements}
import zio.json.ast.Json

import java.time.LocalDate
import scala.util.Try

/** Keep reported totals separate from Finnhub's sales-per-share metric series. */
private[service] object FinnhubIncomeStatements {
  // SEC unit IDs may wrap the ISO code (for example _USD or UNIT_STANDARD_USD_...).
  // Opaque references such as UREF_123 do not establish a report currency.
  private[service] def currencyCode(unit: String): Option[String] = {
    val value = unit.toUpperCase(java.util.Locale.ROOT)
    val code = value.stripPrefix("UNIT_STANDARD_").stripPrefix("UNIT_").stripPrefix("U_").stripPrefix("_")
      .takeWhile(_ != '_')
    Try(java.util.Currency.getInstance(code)).toOption.map(_.getCurrencyCode)
  }

  def normalizeCurrencies(statements: IncomeStatements): IncomeStatements = {
    def normalize(period: IncomePeriod): IncomePeriod = period.copy(currency = period.currency.flatMap(currencyCode))
    IncomeStatements(statements.annual.map(normalize), statements.quarterly.map(normalize))
  }

  def decode(annual: Json.Obj, quarterly: Json.Obj): IncomeStatements = {
    val yearly = reports(annual)
    val quarters = reports(quarterly)
    val individual = (quarters ++ yearly).flatMap { current =>
      val quarter = if (yearly.contains(current)) 4 else current.quarter
      if (current.days <= 120 && quarter >= 1 && quarter <= 4) Some(period(current))
      else if (quarter >= 2 && quarter <= 4) {
        quarters.find(previous => previous.year == current.year && previous.quarter == quarter - 1 &&
          previous.start == current.start && previous.end.isBefore(current.end)).map { previous =>
          // SEC 10-Q income statements are often cumulative YTD. Difference only additive
          // totals; EPS cannot be subtracted because weighted share counts can change.
          val totals = current.points.flatMap { case (name, (value, unit)) =>
            Option.when(!name.contains("PerShare"))(previous.points.get(name))
              .flatten.filter(_._2 == unit).map(old => name -> (value - old._1, unit))
          }
          period(current.copy(points = totals))
        }
      } else None
    }.filter(hasValues).groupBy(_.date).toSeq.sortBy(_._1).map(_._2.head)
    IncomeStatements(yearly.map(period).filter(hasValues), individual)
  }

  private case class Report(start: LocalDate, end: LocalDate, filed: String, year: Int, quarter: Int,
      points: Map[String, (Double, Option[String])]) {
    def days: Long = java.time.temporal.ChronoUnit.DAYS.between(start, end)
  }

  private def hasValues(value: IncomePeriod): Boolean =
    value.revenue.isDefined || value.netIncome.isDefined || value.eps.isDefined

  private def reports(root: Json.Obj): Seq[Report] = {
    val rows = root.get("data").collect { case Json.Arr(values) => values.toSeq }.getOrElse(Seq.empty)
    rows.flatMap {
      case row: Json.Obj =>
        for {
          end <- text(row, "endDate").flatMap(date)
          start <- text(row, "startDate").flatMap(date)
          if !start.isAfter(end)
          report <- row.get("report").collect { case value: Json.Obj => value }
          income <- report.get("ic")
          year = row.get("year").flatMap(number).map(_.toInt).getOrElse(end.getYear)
          quarter = row.get("quarter").flatMap(number).map(_.toInt).getOrElse(0)
        } yield (end, text(row, "filedDate").getOrElse(""), start, year, quarter, income)
      case _ => None
    }.groupBy(_._1).toSeq.sortBy(_._1).flatMap { case (end, revisions) =>
      val revision = revisions.maxBy(_._2)
      val income = revision._6
      val points: Map[String, (Double, Option[String])] = income match {
        case Json.Arr(entries) => entries.collect { case entry: Json.Obj => entry }.flatMap { entry =>
          for {
            concept <- text(entry, "concept")
            value <- entry.get("value").flatMap(number)
          } yield concept.stripPrefix("us-gaap_").stripPrefix("us-gaap:")
            .stripPrefix("ifrs-full_").stripPrefix("ifrs-full:") ->
              (value, text(entry, "unit").map(_.toUpperCase(java.util.Locale.ROOT)))
        }.toMap
        case obj: Json.Obj => obj.fields.flatMap { case (name, value) => number(value).map(v => name -> (v, None)) }.toMap
        case _ => Map.empty
      }
      Some(Report(revision._3, end, revision._2, revision._4, revision._5, points))
    }
  }

  private def period(report: Report): IncomePeriod = {
      def point(names: String*): Option[(Double, Option[String])] = names.iterator.flatMap(report.points.get).nextOption()
      // Banks report net revenue after interest expense. Prefer consolidated totals
      // to ASC 606 fee/segment revenue when both appear in a statement.
      val bankRevenue = for {
        interest <- point("InterestIncomeExpenseNet")
        other <- point("NoninterestIncome") if interest._2 == other._2
        total = interest._1 + other._1 if total.isFinite
      } yield total -> interest._2
      val revenue = point("RevenuesNetOfInterestExpense", "Revenues", "Revenue", "RegulatedAndUnregulatedOperatingRevenue")
        .orElse(bankRevenue).orElse(point(
        "RevenueFromContractWithCustomerExcludingAssessedTax",
        "RevenueFromContractWithCustomerIncludingAssessedTax", "SalesRevenueNet", "SalesRevenueGoodsNet", "SalesRevenueServicesNet"))
      val profit = point("NetIncomeLoss", "ProfitLoss")
      val eps = point("EarningsPerShareBasic", "EarningsPerShareDiluted", "BasicEarningsLossPerShare", "DilutedEarningsLossPerShare")
      val currency = revenue.flatMap(_._2).orElse(profit.flatMap(_._2)).flatMap(currencyCode)
      val margin = for {
        sales <- revenue if sales._1 > 0
        net <- profit if sales._2 == net._2
        value = net._1 / sales._1 * 100 if value.isFinite
      } yield value
      IncomePeriod(report.end, currency, revenue.map(_._1), profit.map(_._1), margin, eps.map(_._1))
  }

  private def text(row: Json.Obj, name: String): Option[String] =
    row.get(name).collect { case Json.Str(value) => value }

  private def date(value: String): Option[LocalDate] = Try(LocalDate.parse(value.take(10))).toOption

  private def number(value: Json): Option[Double] = (value match {
    case Json.Num(value) => Some(value.doubleValue())
    case Json.Str(value) => value.toDoubleOption
    case _ => None
  }).filter(_.isFinite)
}
