package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.{IncomePeriod, IncomeStatements}
import io.greensheep.trading.core.utils.{HttpStatusException, LocalConfig, TransientRetry}
import io.greensheep.trading.dataservice.MongoDataRepo
import zio.*
import zio.http.{Client, QueryParams, Request, Status, URL}
import zio.json.*
import zio.json.ast.Json

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import scala.util.Try

object IncomeStatementsService {
  private val exchanges = Map("us" -> "US", "ro" -> "RO")
  private val tickerPattern = "[A-Za-z0-9][A-Za-z0-9.-]{0,31}".r

  def load(country: String, ticker: String): ZIO[MongoDatabase & Client, Throwable, IncomeStatements] =
    if (!tickerPattern.matches(ticker)) ZIO.fail(new IllegalArgumentException("Invalid ticker"))
    else {
      for {
        now <- Clock.instant
        cached <- MongoDataRepo.getCachedIncomeStatements(ticker)
        result <- cached.filter(record => record.fetchedAt.plus(7, ChronoUnit.DAYS).isAfter(now)) match {
          case Some(record) => ZIO.succeed(record.statements)
          case None =>
            val stored = cached.map(_.statements).getOrElse(IncomeStatements.empty)
            (exchanges.get(country), LocalConfig.get("eodhd.api.key")) match {
              case (Some(exchange), Some(key)) =>
                MongoDataRepo.getStockUniverse.map(_.symbols.contains(ticker)).catchSome {
                  case _: NoSuchElementException => ZIO.succeed(false)
                }.flatMap { listed =>
                  if (!listed) ZIO.succeed(stored)
                  else fetch(s"$ticker.$exchange", key)
                    .tap(statements => MongoDataRepo.cacheIncomeStatements(
                      MongoDataRepo.CachedIncomeStatements(ticker, now, statements)
                    ))
                    .catchAll(error => cached.fold[Task[IncomeStatements]](ZIO.fail(error))(record => ZIO.succeed(record.statements)))
                }
              case _ => ZIO.succeed(stored)
            }
        }
      } yield result
    }

  private def fetch(symbol: String, key: String): ZIO[Client, Throwable, IncomeStatements] =
    ZIO.scoped {
      for {
        base <- ZIO.fromEither(URL.decode("https://eodhd.com/api/v1.1/fundamentals"))
        url = (base / symbol).addQueryParams(QueryParams(
          "api_token" -> key,
          "fmt" -> "json",
          "filter" -> "Financials::Income_Statement,Earnings"
        ))
        client <- ZIO.service[Client]
        response <- client.request(Request.get(url))
        statements <- if (response.status == Status.NotFound) ZIO.succeed(IncomeStatements.empty)
          else if (!response.status.isSuccess)
            ZIO.fail(HttpStatusException(response.status.code, "EODHD fundamentals request failed"))
          else response.body.asString.flatMap(body => ZIO.fromEither(decode(body))
            .orElseFail(new IllegalStateException("Invalid EODHD fundamentals response")))
      } yield statements
    }.retry(TransientRetry.http).mapError {
      case error: HttpStatusException => error
      case _ => new IllegalStateException("EODHD fundamentals request could not be completed")
    }

  private[service] def decode(body: String): Either[String, IncomeStatements] =
    body.fromJson[Json.Obj].map { root =>
      val statements = root.get("Financials::Income_Statement").flatMap(obj)
      val earnings = root.get("Earnings").flatMap(obj)
      val currency = statements.flatMap(field(_, "currency_symbol"))

      def series(period: String, epsPeriod: String): Seq[IncomePeriod] = {
        val epsByDate = earnings.flatMap(_.get(epsPeriod)).flatMap(obj)
        val rows = statements.flatMap(_.get(period)).flatMap(obj)
        rows.toSeq.flatMap(_.fields).flatMap { case (date, value) =>
          for {
            end <- Try(LocalDate.parse(date)).toOption
            row <- obj(value)
            revenue = number(row, "totalRevenue")
            netIncome = number(row, "netIncome")
            reportCurrency = field(row, "currency_symbol").orElse(currency)
            margin = for { sales <- revenue if sales > 0; profit <- netIncome } yield profit / sales * 100
            eps = epsByDate.flatMap(_.get(date)).flatMap(obj)
              .filter(earning => field(earning, "currency").forall(reportCurrency.contains))
              .flatMap(number(_, "epsActual"))
            if revenue.isDefined || netIncome.isDefined || eps.isDefined
          } yield IncomePeriod(end, reportCurrency, revenue, netIncome, margin.filter(_.isFinite), eps)
        }.sortBy(_.date)
      }

      IncomeStatements(series("yearly", "Annual"), series("quarterly", "History"))
    }

  private def obj(value: Json): Option[Json.Obj] = value match {
    case value: Json.Obj => Some(value)
    case _ => None
  }

  private def field(row: Json.Obj, name: String): Option[String] =
    row.get(name).collect { case Json.Str(value) if value.trim.nonEmpty => value.trim }

  private def number(row: Json.Obj, name: String): Option[Double] =
    row.get(name).flatMap {
      case Json.Num(value) => Some(value.doubleValue())
      case Json.Str(value) => value.toDoubleOption
      case _ => None
    }.filter(_.isFinite)
}
