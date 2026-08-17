package io.greensheep.providers.polygon

import io.greensheep.providers.polygon.PolygonAsyncApi.JobHandler
import io.greensheep.providers.polygon.datatypes.{Aggregate, ApiResponse, SplitData, Ticker, TickerDetails}
import io.greensheep.providers.polygon.requests.{AggregatesRequest, ApiRequest, GroupedDailyRequest, PageRequest, SplitsRequest, TickerDetailsRequest, TickersRequest}
import io.greensheep.trading.core.utils.TransientRetry
import zio.*
import zio.http.{Client, URL}
import zio.json.JsonDecoder
import zio.stream.*

import java.time.LocalDate

object PolygonAsyncApi {

  case class JobHandler[X](
      request: ApiRequest[X],
      promise: Promise[Throwable, ApiResponse[X]]
  )

  def buildLayer: RLayer[Client, PolygonAsyncApi] =
    ZLayer.fromZIO(build())

  def build(): ZIO[Client, Nothing, PolygonAsyncApi] =
    for {
      queue <- Queue.bounded[JobHandler[?]](512)
      stream = ZStream
        .fromQueue(queue)
        .schedule(Schedule.spaced(16.seconds))
        .mapZIO { job =>
          job.request
            .perform()
            .timeoutFail(new RuntimeException("Polygon request timed out"))(30.seconds)
            .retry(TransientRetry.http)
            .flatMap(result => job.promise.succeed(result).unit)
            .catchAll { error =>
              ZIO.logError(s"Polygon job failed: ${error.getMessage}") *>
                job.promise.fail(error).unit
            }
        }
      _ <- stream.runDrain.forkDaemon
    } yield PolygonAsyncApi(queue)
}

class PolygonAsyncApi(queue: Queue[PolygonAsyncApi.JobHandler[?]]) {

  private def doRequest[T](req: ApiRequest[T]): Task[Promise[Throwable, ApiResponse[T]]] =
    for {
      promise <- Promise.make[Throwable, ApiResponse[T]]
      shutdown <- queue.isShutdown
      _ <- ZIO.fail(new RuntimeException("Queue is shutdown")).when(shutdown)
      _ <- queue.offer(PolygonAsyncApi.JobHandler(req, promise))
    } yield promise

  private def pageRequest[T](url: Option[String], resultsQueue: Queue[T])(using
      decoder: JsonDecoder[T]
  ): ZIO[Any, Throwable, Unit] =
    url.filter(_.nonEmpty) match {
      case Some(value) =>
        val decodedUrl = URL.decode(value).toOption.getOrElse {
          throw new RuntimeException(s"Invalid Polygon next_url: $value")
        }
        val request = PageRequest[Seq[T]](decodedUrl)
        for {
          promise <- doRequest[Seq[T]](request)
          response <- promise.await
          results = response.results.getOrElse(Seq.empty)
          _ <- resultsQueue.offerAll(results)
          _ <- pageRequest(response.next_url, resultsQueue)
        } yield ()
      case None =>
        resultsQueue.shutdown
    }

  def getTickers: ZStream[Any, Throwable, Ticker] = ZStream.unwrap {
    val request = TickersRequest(limit = 1000)
    for {
      tickerQueue <- Queue.unbounded[Ticker]
      stream = ZStream.fromQueue(tickerQueue)
      asyncEffect = (
        for {
          promise <- doRequest(request)
          response <- promise.await
          tickers <- ZIO.fromOption(response.results).orElseFail(new RuntimeException("No tickers found"))
          _ <- tickerQueue.offerAll(tickers)
          _ <- pageRequest(response.next_url, tickerQueue)
        } yield ()
      ).tapError(error => ZIO.logError(s"Ticker pagination failed: ${error.getMessage}"))
        .ensuring(tickerQueue.shutdown)
      _ <- asyncEffect.fork
    } yield stream
  }

  def getTickerDetails(symbol: String): ZIO[Any, Throwable, TickerDetails] = {
    val request = TickerDetailsRequest(symbol)
    for {
      promise <- doRequest[TickerDetails](request)
      response <- promise.await
      details <- response.asZIO.flatMap(opt =>
        ZIO.fromOption(opt).orElseFail(new RuntimeException("No details found"))
      )
    } yield details
  }

  def getGroupedDaily(date: LocalDate): ZIO[Any, Throwable, Seq[Aggregate]] = {
    val request = GroupedDailyRequest(date.toString)
    for {
      promise <- doRequest(request)
      response <- promise.await
      results <- response.asZIO
    } yield results.getOrElse(Seq.empty)
  }

  def getAggregates(ticker: String, fromDate: LocalDate): ZIO[Any, Throwable, Seq[Aggregate]] = {
    val to = LocalDate.now().minusDays(1)
    val request = AggregatesRequest(stocksTicker = ticker, from = fromDate.toString, to = to.toString)
    for {
      promise <- doRequest(request)
      response <- promise.await
    } yield response.results.getOrElse(Seq.empty)
  }

  def getSplits(from: LocalDate, to: LocalDate): Task[Seq[SplitData]] =
    collectAll(SplitsRequest(from.toString, to.toString, 1000))

  def getReverseSplits(from: LocalDate, to: LocalDate): Task[Seq[SplitData]] =
    collectAll(SplitsRequest(from.toString, to.toString, 1000, reverse = true))

  private def collectAll[T: JsonDecoder](request: ApiRequest[Seq[T]]): Task[Seq[T]] =
    for {
      promise <- doRequest(request)
      response <- promise.await
      first = response.results.getOrElse(Seq.empty)
      rest <- collectPages[T](response.next_url)
    } yield first ++ rest

  private def collectPages[T: JsonDecoder](nextUrl: Option[String]): Task[Seq[T]] =
    nextUrl.filter(_.nonEmpty) match {
      case None => ZIO.succeed(Seq.empty)
      case Some(value) =>
        val decodedUrl = URL.decode(value).toOption.getOrElse {
          throw new RuntimeException(s"Invalid Polygon next_url: $value")
        }
        val request = PageRequest[Seq[T]](decodedUrl)
        for {
          promise <- doRequest(request)
          response <- promise.await
          page = response.results.getOrElse(Seq.empty)
          rest <- collectPages[T](response.next_url)
        } yield page ++ rest
    }
}
