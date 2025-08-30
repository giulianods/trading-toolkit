package io.greensheep.trading.api
import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.core.types.{DailyQuoteData, Watchlist}
import io.greensheep.trading.dataservice.MongoDataRepo
import io.greensheep.trading.api.services.ChartServices
import zio.*
import zio.http.*
import zio.json.EncoderOps


import java.io.{PrintWriter, StringWriter}
object ApiEndpoints {



  def handleError(t: Throwable): Response = {
    val stringWriter = new StringWriter()
    t.printStackTrace(new PrintWriter(stringWriter))
    val stackTrace = stringWriter.toString
    (Response.text(stackTrace).status(Status.InternalServerError))
  }

  def buildRestApi: HttpApp[MongoDatabase] = {
    Routes(
      // This line works fine
      (Method.GET / "quotes" / string("ticker")) -> {
        Handler.fromFunctionZIO[(String, Request)]{
          case (ticker, _) => {
            for {
              _ <- ZIO.logInfo(s"Received request for ticker: $ticker")
              data <- MongoDataRepo.getPriceData(ticker)
            } yield Response.json(data.toJson)
          }
        }.mapError(handleError)
      },
      (Method.GET / "watchlists" / string("name")) -> {
        Handler.fromFunctionZIO[(String, Request)]{
          case (name, _) => {
            for {
              _ <- ZIO.logInfo(s"Received request for watchlist: $name")
              watchlist <- MongoDataRepo.getWatchlistLatest(name)
              symbolsCsv  = watchlist.tickers.mkString("\n")
            } yield Response.text(symbolsCsv)
          }
        }.mapError(handleError)
      },
      (Method.GET / "chart" / string("ticker")) -> {
        Handler.fromFunctionZIO[(String, Request)] {
          case (ticker, _) =>
            for {
              _   <- ZIO.logInfo(s"Received chart request for ticker: $ticker")
              svg <- ChartServices.chartSvg(ticker)
            } yield Response.text(svg).withHeader(Header.Custom("Content-Type", "image/svg+xml"))
        }.mapError(handleError)
      }
    ).toHttpApp
  }


}
