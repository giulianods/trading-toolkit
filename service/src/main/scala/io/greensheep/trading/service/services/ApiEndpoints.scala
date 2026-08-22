package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.{WatchlistHistoryEntry, WatchlistSummary}
import io.greensheep.trading.dataservice.{MarketStore, MongoDataRepo}
import io.greensheep.trading.service.app.{AppHealth, AppStatus}
import zio.*
import zio.http.*
import zio.json.EncoderOps

import java.lang
import java.time.LocalDate
import scala.util.Try

object ApiEndpoints {

  private val MaxHistoryDays = 400

  def handleError(error: Throwable): Response = {
    val status = error match {
      case _: NoSuchElementException => Status.NotFound
      case _: IllegalArgumentException => Status.BadRequest
      case _ => Status.InternalServerError
    }
    val message =
      if (status == Status.InternalServerError) "Internal server error"
      else Option(error.getMessage).getOrElse(status.text)
    Response.text(message).status(status)
  }

  private enum FinancialsCategory:
    case Annual, Quarterly

  private def parseCategoryFilter(param: Option[String]): Either[IllegalArgumentException, Set[FinancialsCategory]] =
    param match {
      case None => Right(Set(FinancialsCategory.Annual, FinancialsCategory.Quarterly))
      case Some(value) =>
        val tokens = value.split(",").map(_.trim).filter(_.nonEmpty)
        if (tokens.isEmpty) Right(Set(FinancialsCategory.Annual, FinancialsCategory.Quarterly))
        else {
          val parsed = tokens.map(_.toLowerCase).map {
            case "annual" => Right(FinancialsCategory.Annual)
            case "quarterly" => Right(FinancialsCategory.Quarterly)
            case _ => Left(new IllegalArgumentException("category must be 'annual' or 'quarterly'"))
          }
          parsed.find(_.isLeft) match {
            case Some(Left(error)) => Left(error)
            case _ => Right(parsed.collect { case Right(category) => category }.toSet)
          }
        }
    }

  private def parseBoundedInt(name: String, param: Option[String], default: Int, min: Int, max: Int): Either[IllegalArgumentException, Int] =
    param match {
      case None => Right(default)
      case Some(value) =>
        Try(value.toInt).toOption match {
          case Some(parsed) if parsed >= min && parsed <= max => Right(parsed)
          case Some(_) => Left(new IllegalArgumentException(s"$name must be between $min and $max"))
          case None => Left(new IllegalArgumentException(s"$name must be an integer"))
        }
    }

  private def withMarket(
      store: MarketStore,
      country: String,
      provider: String
  )(effect: ZIO[MongoDatabase, Throwable, Response]): UIO[Response] =
    store
      .databaseZIO(country, provider)
      .flatMap(db => effect.provide(ZLayer.succeed(db)))
      .catchAll(error => ZIO.succeed(handleError(error)))

  private def pingEnabled(store: MarketStore): UIO[String] =
    ZIO
      .foreach(store.enabled) { market =>
        MongoDataRepo.ping.provide(ZLayer.succeed(store.database(market))).either
      }
      .map(results => if (results.forall(_.isRight)) "ok" else "error")

  private def quotesResponse(ticker: String, req: Request): ZIO[MongoDatabase, Throwable, Response] = {
    val accept = req.headers.get("Accept").getOrElse("application/json")
    MongoDataRepo.getPriceData(ticker).map { data =>
      if (accept.contains("text/csv")) {
        val csvData = new lang.StringBuilder
        csvData.append("date,open,high,low,close,volume\n")
        data.quotes.foreach { quote =>
          csvData.append(s"${quote.day},${quote.o},${quote.h},${quote.l},${quote.c},${quote.v}\n")
        }
        Response.text(csvData.toString)
      } else Response.json(data.toJson)
    }
  }

  private def financialsResponse(req: Request): ZIO[MongoDatabase, Throwable, Response] =
    for {
      tickersParam <- ZIO
        .fromOption(req.url.queryParams.get("tickers"))
        .orElseFail(new IllegalArgumentException("Missing required query parameter: tickers"))
      tickers = tickersParam.split(",").map(_.trim).filter(_.nonEmpty).toSeq
      _ <- ZIO.fail(new IllegalArgumentException("At least one ticker is required")).when(tickers.isEmpty)
      categories <- ZIO.fromEither(parseCategoryFilter(req.url.queryParams.get("category")))
      includeAnnual = categories.contains(FinancialsCategory.Annual)
      includeQuarterly = categories.contains(FinancialsCategory.Quarterly)
      financialsMap <- ZIO.foreach(tickers) { ticker =>
        MongoDataRepo
          .getFinancials(ticker)
          .map(financials =>
            Some(
              ticker -> financials.copy(
                annual = if (includeAnnual) financials.annual else Seq.empty,
                quarterly = if (includeQuarterly) financials.quarterly else Seq.empty
              )
            )
          )
          .catchSome { case _: NoSuchElementException =>
            ZIO.logDebug(s"No financials found for ticker: $ticker").as(None)
          }
      }.map(_.flatten.toMap)
    } yield Response.json(financialsMap.toJson)

  private def watchlistHistoryResponse(req: Request): ZIO[MongoDatabase, Throwable, Response] =
    for {
      startParam <- ZIO
        .fromOption(req.url.queryParams.get("start"))
        .orElseFail(new IllegalArgumentException("Missing required query parameter: start"))
      endParam <- ZIO
        .fromOption(req.url.queryParams.get("end"))
        .orElseFail(new IllegalArgumentException("Missing required query parameter: end"))
      startDate <- ZIO.attempt(LocalDate.parse(startParam)).mapError(_ => new IllegalArgumentException(s"Invalid start date: $startParam"))
      endDate <- ZIO.attempt(LocalDate.parse(endParam)).mapError(_ => new IllegalArgumentException(s"Invalid end date: $endParam"))
      _ <- ZIO.fail(new IllegalArgumentException("start must be on or before end")).when(endDate.isBefore(startDate))
      _ <- ZIO
        .fail(new IllegalArgumentException(s"range must be at most $MaxHistoryDays days"))
        .when(java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) > MaxHistoryDays)
      watchlists <- MongoDataRepo.getWatchlistsBetween(startDate, endDate)
      history = watchlists
        .groupBy(_.day)
        .toSeq
        .sortBy(_._1)
        .map { case (day, lists) =>
          val summaries = lists.sortBy(_.screenName).map(wl => WatchlistSummary(wl.screenName, wl.tickers.size))
          WatchlistHistoryEntry(day, summaries)
        }
    } yield Response.json(history.toJson)

  private def watchlistResponse(name: String, req: Request): ZIO[MongoDatabase, Throwable, Response] =
    req.url.queryParams.get("day") match {
      case Some(dayParam) =>
        for {
          day <- ZIO.attempt(LocalDate.parse(dayParam)).mapError(_ => new IllegalArgumentException(s"Invalid day: $dayParam"))
          watchlist <- MongoDataRepo.getWatchlist(name, day)
        } yield Response.json(watchlist.toJson)
      case None =>
        MongoDataRepo.getWatchlistLatest(name).map(watchlist => Response.json(watchlist.toJson))
    }

  private def descriptionResponse(ticker: String): ZIO[MongoDatabase, Throwable, Response] =
    MongoDataRepo.getStockDescription(ticker).map(data => Response.json(data.toJson))

  private def breadthResponse(req: Request): ZIO[MongoDatabase, Throwable, Response] =
    (req.url.queryParams.get("start"), req.url.queryParams.get("end")) match {
      case (Some(startParam), Some(endParam)) =>
        for {
          startDate <- ZIO.attempt(LocalDate.parse(startParam)).mapError(_ => new IllegalArgumentException(s"Invalid start date: $startParam"))
          endDate <- ZIO.attempt(LocalDate.parse(endParam)).mapError(_ => new IllegalArgumentException(s"Invalid end date: $endParam"))
          _ <- ZIO.fail(new IllegalArgumentException("start must be on or before end")).when(endDate.isBefore(startDate))
          _ <- ZIO
            .fail(new IllegalArgumentException(s"range must be at most $MaxHistoryDays days"))
            .when(java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) > MaxHistoryDays)
          indicators <- MongoDataRepo.getMarketBreadthBetween(startDate, endDate)
        } yield Response.json(indicators.sortBy(_.date).toJson)
      case _ =>
        for {
          days <- ZIO.fromEither(
            parseBoundedInt("days", req.url.queryParams.get("days"), default = 100, min = 1, max = MaxHistoryDays)
          )
          indicators <- MongoDataRepo.getMarketBreadthLatest(days)
        } yield Response.json(indicators.sortBy(_.date).toJson)
    }

  def routes(store: MarketStore): Routes[AppHealth, Response] = {
    val status = Routes(
      (Method.GET / "status") -> Handler.fromFunctionZIO[Request] { _ =>
        for {
          jobs <- AppHealth.snapshot
          cronOn <- AppHealth.cronStarted
          mongo <- pingEnabled(store)
        } yield Response.json(
          AppStatus(
            status = "running",
            mongo = mongo,
            web = "ok",
            cron = if (cronOn) "running" else "stopped",
            jobs = jobs
          ).toJson
        )
      }
    )

    val marketData = Routes(
      (Method.GET / "markets" / string("country") / string("provider") / "quotes" / string("ticker")) ->
        Handler.fromFunctionZIO[(String, String, String, Request)] { case (country, provider, ticker, req) =>
          withMarket(store, country, provider)(quotesResponse(ticker, req))
        },
      (Method.GET / "markets" / string("country") / string("provider") / "financials") ->
        Handler.fromFunctionZIO[(String, String, Request)] { case (country, provider, req) =>
          withMarket(store, country, provider)(financialsResponse(req))
        },
      (Method.GET / "markets" / string("country") / string("provider") / "watchlists" / "history") ->
        Handler.fromFunctionZIO[(String, String, Request)] { case (country, provider, req) =>
          withMarket(store, country, provider)(watchlistHistoryResponse(req))
        },
      (Method.GET / "markets" / string("country") / string("provider") / "watchlists" / string("name")) ->
        Handler.fromFunctionZIO[(String, String, String, Request)] { case (country, provider, name, req) =>
          withMarket(store, country, provider)(watchlistResponse(name, req))
        },
      (Method.GET / "markets" / string("country") / string("provider") / "description" / string("ticker")) ->
        Handler.fromFunctionZIO[(String, String, String, Request)] { case (country, provider, ticker, _) =>
          withMarket(store, country, provider)(descriptionResponse(ticker))
        },
      (Method.GET / "markets" / string("country") / string("provider") / "breadth" / "indicators") ->
        Handler.fromFunctionZIO[(String, String, Request)] { case (country, provider, req) =>
          withMarket(store, country, provider)(breadthResponse(req))
        }
    )

    val aliases = Routes(
      (Method.GET / "quotes" / string("ticker")) ->
        Handler.fromFunctionZIO[(String, Request)] { case (ticker, req) =>
          withMarket(store, "us", "massive")(quotesResponse(ticker, req))
        },
      (Method.GET / "financials") ->
        Handler.fromFunctionZIO[Request] { req =>
          withMarket(store, "us", "massive")(financialsResponse(req))
        },
      (Method.GET / "watchlists" / "history") ->
        Handler.fromFunctionZIO[Request] { req =>
          withMarket(store, "us", "massive")(watchlistHistoryResponse(req))
        },
      (Method.GET / "watchlists" / string("name")) ->
        Handler.fromFunctionZIO[(String, Request)] { case (name, req) =>
          withMarket(store, "us", "massive")(watchlistResponse(name, req))
        },
      (Method.GET / "description" / string("ticker")) ->
        Handler.fromFunctionZIO[(String, Request)] { case (ticker, _) =>
          withMarket(store, "us", "massive")(descriptionResponse(ticker))
        },
      (Method.GET / "breadth" / "indicators") ->
        Handler.fromFunctionZIO[Request] { req =>
          withMarket(store, "us", "massive")(breadthResponse(req))
        }
    )

    status ++ marketData ++ aliases
  }
}
