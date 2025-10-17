package io.greensheep.trading.api
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.{DailyQuoteData, Watchlist}
import io.greensheep.trading.dataservice.MongoDataRepo
import zio.*
import zio.http.*
import zio.json.EncoderOps

import java.io.{PrintWriter, StringWriter}
import java.lang
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
      (Method.GET / "watchlists" / string("name")) -> {
        Handler.fromFunctionZIO[(String, Request)]{
          case (name, _) => {
            for {
              _ <- ZIO.logInfo(s"Received request for watchlist: $name")
              watchlist <- MongoDataRepo.getWatchlistLatest(name)
            } yield Response.json(watchlist.toJson)
          }
        }.mapError(handleError)
      },
      (Method.GET / "chart" / string("ticker")) -> {
        Handler.fromFunctionZIO[(String, Request)] {
          case (ticker, req) =>
            val days = req.url.queryParams.get("days").map(_.toInt).getOrElse(100)
            for {
              chartService <- ZIO.service[ChartServices]
              svg  <- chartService.chartSvg(ticker, days)
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
    ).toHttpApp
  }


}
