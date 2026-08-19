package io.greensheep.providers.massive


import io.greensheep.providers.massive.datatypes.{ApiResponse, Ticker, TickerDetails}
import io.greensheep.providers.massive.datatypes.financials.{FinancialData, FinancialSnapshot}
import io.greensheep.providers.massive.requests.{StockFinancialsRequest, TickerDetailsRequest, TickersRequest}
import zio.*
import zio.http.*
import zio.stream.ZStream
import zio.json.DecoderOps

import scala.language.implicitConversions
import scala.util.{Failure, Success}

class ReferenceClient(config: MassiveSettings) {


  def getTickers(request: TickersRequest): ZStream[Any, Throwable, Ticker] = {
    val url = request.buildRequest(config.baseUrl)
    val executor = new PaginatedRequestExecutor[Ticker](url.toJavaURI.toString, config)
    executor.results
  }


  def getTickerDetails(request: TickerDetailsRequest): ZIO[Any, Throwable, TickerDetails] = {
    val url = request.buildRequest(config.baseUrl)
    val result = ZIO.scoped {
      for {
        res <- Client.request(Request(
          headers = config.headers,
          url = config.authenticate(URL.decode(url).toOption.get),
          method = Method.GET,
        ))
        resultString <- res.body.asString
        decodedJson = resultString.fromJson[ApiResponse[TickerDetails]]
        apiResponse <- ZIO.fromEither(decodedJson).mapError(RuntimeException(_))
        _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
        result <- ZIO.fromEither(apiResponse.results.toRight(new RuntimeException(s"Missing result from json: $decodedJson")))
      } yield result
    }
    result.provideLayer(Client.default)
  }


  def getStockFinancials(request: StockFinancialsRequest): ZStream[Any, Throwable, FinancialData] = {
    val url = request.buildRequest(config.baseUrl)
    val executor = new PaginatedRequestExecutor[FinancialData](url, config)
    executor.results
  }
}
