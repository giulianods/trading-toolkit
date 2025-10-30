package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.api.ChartServices
import io.greensheep.trading.core.types.{DailyQuoteData, Watchlist, WatchlistHistoryEntry, WatchlistSummary}
import io.greensheep.trading.dataservice.MongoDataRepo
import io.greensheep.trading.service.services.ScreenerServices
import zio.*
import zio.http.*
import zio.json.EncoderOps

import java.io.{PrintWriter, StringWriter}
import java.lang
import java.time.LocalDate
import io.greensheep.trading.core.screens.BreadthIndicators
object ApiEndpoints {



  def handleError(t: Throwable): Response = {
    val stringWriter = new StringWriter()
    t.printStackTrace(new PrintWriter(stringWriter))
    val stackTrace = stringWriter.toString
    (Response.text(stackTrace).status(Status.InternalServerError))
  }

  def buildRestApi: HttpApp[MongoDatabase & ChartServices] = {
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
        Handler.fromFunctionZIO[Request]{
          case req => {
            val noDays = req.url.queryParams.get("days").map(_.toInt).getOrElse(100)
            for {
              stockMarket <- ScreenerServices.LoadQuotes
              indicators <- ZIO.succeed(BreadthIndicators.buildHistory(stockMarket, noDays))
            } yield Response.json(indicators.toJson)
          }
        }.mapError(handleError)
      }
    ).toHttpApp
  }


}
