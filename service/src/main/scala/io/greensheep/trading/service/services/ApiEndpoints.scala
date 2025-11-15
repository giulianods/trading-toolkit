package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.api.ChartServices
import io.greensheep.trading.api.ChartServices.FinancialChartCategory
import io.greensheep.trading.core.types.{DailyQuoteData, Watchlist, WatchlistHistoryEntry, WatchlistSummary}
import io.greensheep.trading.dataservice.MongoDataRepo
import io.greensheep.trading.service.services.ScreenerServices
import zio.*
import zio.http.*
import zio.json.EncoderOps

import java.io.{PrintWriter, StringWriter}
import java.lang
import java.time.LocalDate
import scala.util.Try

object ApiEndpoints {



  def handleError(t: Throwable): Response = {
    val stringWriter = new StringWriter()
    t.printStackTrace(new PrintWriter(stringWriter))
    val stackTrace = stringWriter.toString
    (Response.text(stackTrace).status(Status.InternalServerError))
  }

  private def parseCategoryFilter(param: Option[String]): Either[IllegalArgumentException, Set[FinancialChartCategory]] = {
    param match {
      case None => Right(FinancialChartCategory.defaults)
      case Some(value) =>
        val tokens = value.split(",").map(_.trim).filter(_.nonEmpty)
        if (tokens.isEmpty) {
          Right(FinancialChartCategory.defaults)
        } else {
          val parsed = tokens.map(FinancialChartCategory.fromString)
          if (parsed.contains(None)) {
            Left(new IllegalArgumentException("category must be 'annual' or 'quarterly'"))
          } else {
            Right(parsed.flatten.toSet)
          }
        }
    }
  }

  private def parseSingleCategory(param: Option[String]): Either[IllegalArgumentException, FinancialChartCategory] = {
    param match {
      case None => Right(FinancialChartCategory.Annual)
      case Some(value) if value.trim.isEmpty => Right(FinancialChartCategory.Annual)
      case Some(value) =>
        FinancialChartCategory
          .fromString(value.trim)
          .toRight(new IllegalArgumentException("category must be 'annual' or 'quarterly'"))
    }
  }

  private def parseBoundedInt(
      name: String,
      param: Option[String],
      default: Int,
      min: Int,
      max: Int
  ): Either[IllegalArgumentException, Int] = {
    param match {
      case None => Right(default)
      case Some(value) =>
        Try(value.toInt).toOption match {
          case Some(parsed) if parsed >= min && parsed <= max => Right(parsed)
          case Some(_) => Left(new IllegalArgumentException(s"$name must be between $min and $max"))
          case None => Left(new IllegalArgumentException(s"$name must be an integer"))
        }
    }
  }

  def financialsRoutes(
      mongoRepo: ZLayer[Any, Throwable, MongoDatabase],
      chartServicesLayer: ZLayer[Any, Throwable, ChartServices]
  ): Routes[Any, Response] = {
    Routes(
      (Method.GET / "financials") -> {
        Handler.fromFunctionZIO[Request] { req =>
          val result = for {
            tickersParam <- ZIO.fromOption(req.url.queryParams.get("tickers"))
              .orElseFail(new IllegalArgumentException("Missing required query parameter: tickers"))
            tickers = tickersParam.split(",").map(_.trim).filter(_.nonEmpty).toSeq
            _ <- ZIO.fail(new IllegalArgumentException("At least one ticker is required"))
              .when(tickers.isEmpty)
            categories <- ZIO.fromEither(parseCategoryFilter(req.url.queryParams.get("category")))
            includeAnnual = categories.contains(FinancialChartCategory.Annual)
            includeQuarterly = categories.contains(FinancialChartCategory.Quarterly)
            financialsMap <- ZIO.foreach(tickers) { ticker =>
              MongoDataRepo.getFinancials(ticker)
                .map(financials => Some(ticker -> financials.copy(
                  annual = if (includeAnnual) financials.annual else Seq.empty,
                  quarterly = if (includeQuarterly) financials.quarterly else Seq.empty
                )))
                .catchSome {
                  case _: NoSuchElementException =>
                    ZIO.logDebug(s"No financials found for ticker: $ticker").as(None)
                }
            }.map(_.flatten.toMap)
          } yield Response.json(financialsMap.toJson)

          result.catchSome { case e: IllegalArgumentException =>
            ZIO.succeed(Response.text(e.getMessage).status(Status.BadRequest))
          }.provideLayer(mongoRepo)
        }.mapError(handleError)
      },
      (Method.GET / "financials" / "chart" / string("ticker")) -> {
        Handler.fromFunctionZIO[(String, Request)] { case (ticker, req) =>
          val result = for {
            category <- ZIO.fromEither(parseSingleCategory(req.url.queryParams.get("category")))
            limit <- ZIO.fromEither(
              parseBoundedInt("limit", req.url.queryParams.get("limit"), default = 5, min = 1, max = 5)
            )
            width <- ZIO.fromEither(
              parseBoundedInt("width", req.url.queryParams.get("width"), default = 320, min = 120, max = 800)
            )
            height <- ZIO.fromEither(
              parseBoundedInt("height", req.url.queryParams.get("height"), default = 200, min = 100, max = 600)
            )
            chartService <- ZIO.service[ChartServices]
            svg <- chartService.financialChartSvg(ticker, category, limit, width, height)
          } yield Response.text(svg).addHeader(Header.Custom("Content-Type", "image/svg+xml"))

          result
            .catchSome {
              case e: IllegalArgumentException =>
                ZIO.succeed(Response.text(e.getMessage).status(Status.BadRequest))
              case _: NoSuchElementException =>
                ZIO.succeed(Response.text(s"No financial data found for $ticker").status(Status.NotFound))
            }
            .provideLayer(chartServicesLayer ++ mongoRepo)
        }.mapError(handleError)
      }
    )
  }

  val OtherRoutes: Routes[MongoDatabase & ChartServices, Response] = {
    Routes(
      // This line works fine
      (Method.GET / "quotes" / string("ticker")) -> {
        Handler.fromFunctionZIO[(String, Request)] {
          case (ticker, req) => {
            val contentType = req.headers.get("Content-Type").getOrElse("application/json")
            for {
              _ <- ZIO.logInfo(s"Received request for ticker: $ticker")
              data <- MongoDataRepo.getPriceData(ticker)
            } yield {
              contentType match {
                case "application/json" => Response.json(data.toJson)
                case "text/csv" =>
                  val csvData = new lang.StringBuilder
                  csvData.append("date,open,high,low,close,volume\n")
                  data.quotes.foreach { q =>
                    csvData.append(s"${q.day},${q.o},${q.h},${q.l},${q.c},${q.v}\n")
                  }
                  Response.text(csvData.toString)
                case _ => Response.text(s"Unsupported Content-Type $contentType").status(Status.BadRequest)
              }
            }
          }.mapError(handleError)
        }
      },
      (Method.GET / "watchlists" / "history") -> {
        Handler.fromFunctionZIO[Request] { req =>
          val result = for {
            startParam <- ZIO.fromOption(req.url.queryParams.get("start"))
              .orElseFail(new IllegalArgumentException("Missing required query parameter: start"))
            endParam <- ZIO.fromOption(req.url.queryParams.get("end"))
              .orElseFail(new IllegalArgumentException("Missing required query parameter: end"))
            startDate <- ZIO.attempt(LocalDate.parse(startParam))
              .mapError(_ => new IllegalArgumentException(s"Invalid start date: $startParam"))
            endDate <- ZIO.attempt(LocalDate.parse(endParam))
              .mapError(_ => new IllegalArgumentException(s"Invalid end date: $endParam"))
            _ <- ZIO.fail(new IllegalArgumentException("start must be on or before end"))
              .when(endDate.isBefore(startDate))
            watchlists <- MongoDataRepo.getWatchlistsBetween(startDate, endDate)
            history = watchlists
              .groupBy(_.day)
              .toSeq
              .sortBy(_._1)
              .map { case (day, lists) =>
                val summaries = lists
                  .sortBy(_.screenName)
                  .map(wl => WatchlistSummary(wl.screenName, wl.tickers.size))
                WatchlistHistoryEntry(day, summaries)
              }
          } yield Response.json(history.toJson)

          result.catchSome { case e: IllegalArgumentException =>
            ZIO.succeed(Response.text(e.getMessage).status(Status.BadRequest))
          }
        }.mapError(handleError)
      },
      (Method.GET / "watchlists" / string("name")) -> {
        Handler.fromFunctionZIO[(String, Request)]{
          case (name, req) => {
            val upToDay = req.url.queryParams.get("day").getOrElse(LocalDate.now().toString)
            req.url.queryParams.get("day") match {
              case Some(upToDay) =>
                for {
                  day <- ZIO.attempt(LocalDate.parse(upToDay))
                  watchlist <- MongoDataRepo.getWatchlist(name, day)
                    .catchSome {
                      case _: NoSuchElementException =>
                        ScreenerServices.buildScreensForDate(day).flatMap(_ => MongoDataRepo.getWatchlist(name, day))
                    }
                } yield Response.json(watchlist.toJson)
              case None =>
                MongoDataRepo.getWatchlistLatest(name).map(_.toJson).map(Response.json)
            }
          }
        }.mapError(handleError)
      },
      (Method.GET / "chart" / string("ticker")) -> {
        Handler.fromFunctionZIO[(String, Request)] {
          case (ticker, req) =>
            val days = req.url.queryParams.get("days").map(_.toInt).getOrElse(100)
            for {
              highlightDay <- ZIO.foreach(req.url.queryParams.get("day"))(day => ZIO.attempt(LocalDate.parse(day)))
              chartService <- ZIO.service[ChartServices]
              svg  <- chartService.chartSvg(ticker, days, highlightDay = highlightDay)
            } yield Response.text(svg).addHeader(Header.Custom("Content-Type", "image/svg+xml"))
        }.mapError(handleError)
      },
      (Method.GET / "description" / string("ticker")) -> {
        Handler.fromFunctionZIO[(String, Request)]{
          case (ticker, _) => {
            for {
              _ <- ZIO.logInfo(s"Received request for ticker: $ticker")
              data <- MongoDataRepo.getStockDescription(ticker)
            } yield Response.json(data.toJson)
          }
        }.mapError(handleError)
      },
      (Method.GET / "breadth" / "indicators") -> {
        Handler.fromFunctionZIO[Request] { req =>
          val response = (req.url.queryParams.get("start"), req.url.queryParams.get("end")) match {
            case (Some(startParam), Some(endParam)) =>
              for {
                startDate <- ZIO
                  .attempt(LocalDate.parse(startParam))
                  .mapError(_ => new IllegalArgumentException(s"Invalid start date: $startParam"))
                endDate <- ZIO
                  .attempt(LocalDate.parse(endParam))
                  .mapError(_ => new IllegalArgumentException(s"Invalid end date: $endParam"))
                _ <- ZIO
                  .fail(new IllegalArgumentException("start must be on or before end"))
                  .when(endDate.isBefore(startDate))
                indicators <- MongoDataRepo.getMarketBreadthBetween(startDate, endDate)
                sorted = indicators.sortBy(_.date)
              } yield Response.json(sorted.toJson)
            case _ =>
              val days = req.url.queryParams
                .get("days")
                .flatMap(value => Try(value.toInt).toOption)
                .getOrElse(100)
              for {
                _ <- ZIO
                  .fail(new IllegalArgumentException("days must be greater than 0"))
                  .when(days <= 0)
                indicators <- MongoDataRepo.getMarketBreadthLatest(days)
                sorted = indicators.sortBy(_.date)
              } yield Response.json(sorted.toJson)
          }

          response.catchSome { case e: IllegalArgumentException =>
            ZIO.succeed(Response.text(e.getMessage).status(Status.BadRequest))
          }
        }.mapError(handleError)
      }
    )
  }

}
