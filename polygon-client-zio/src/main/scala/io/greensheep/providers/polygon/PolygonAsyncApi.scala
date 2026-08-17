package io.greensheep.providers.polygon

import io.greensheep.providers.polygon.PolygonAsyncApi.JobHandler
import io.greensheep.providers.polygon.datatypes.{Aggregate, ApiResponse, SplitData, Ticker, TickerDetails}
import io.greensheep.providers.polygon.requests.{AggregatesRequest, ApiRequest, GroupedDailyRequest, PageRequest, SplitsRequest, TickerDetailsRequest, TickersRequest}
import zio.*
import zio.http.{Client, URL}
import zio.json.JsonDecoder
import zio.stream.*

import java.time.LocalDate
import scala.concurrent.Future

object PolygonAsyncApi {


  case class JobHandler[X](
                          request: ApiRequest[X],
                          promise: Promise[Throwable, ApiResponse[X]]
                       )


  def buildLayer: RLayer[Client, PolygonAsyncApi] = {
    ZLayer.fromZIO(build())
  }


  def build(): ZIO[Client, Nothing, PolygonAsyncApi] = {
    for{
      queue <- Queue.unbounded[JobHandler[?]]
      stream = ZStream.fromQueue(queue)
        .schedule(Schedule.spaced(16.seconds))
        .mapZIO(job => job.request.perform()

          .flatMap(job.promise.succeed)
          .catchAll(th => {
            println(s"Job encountered error: ${th.getMessage}")
            job.promise.fail(th)
          })
        ).forever
      _ <- stream.runDrain.forkDaemon
    } yield PolygonAsyncApi(queue)
  }
}
class PolygonAsyncApi(queue: Queue[JobHandler[?]]) {

  private def doRequest[T](req: ApiRequest[T]): Task[Promise[Throwable, ApiResponse[T]]] = {
    for {
      p <- Promise.make[Throwable, ApiResponse[T]]
      sd <- queue.isShutdown
      _ <- ZIO.fail(new RuntimeException("Queue is shutdown")).when(sd)
      _ <- queue.offer(JobHandler(req, p))
    } yield p
  }

  private def pageRequest[T](url: Option[String], resultsQueue: Queue[T])
                            (implicit decoder: JsonDecoder[T]): ZIO[Any, Throwable, Any] = {
    url match
      case Some(value) =>
        val request = PageRequest[Seq[T]](URL.decode(value).toOption.get)
        for{
          p <- doRequest[Seq[T]](request)
          r <- p.await
          results <- ZIO.fromOption(r.results).mapError(_ => new RuntimeException("No results found"))
          _ <- resultsQueue.offerAll(results)
          _ <- pageRequest(r.next_url, resultsQueue)
        } yield ()
      case None =>
        resultsQueue.shutdown
  }

  def getTickers: ZStream[Any, Throwable, Ticker] =  ZStream.unwrap {
    val request = TickersRequest(limit=1000)
    for{
      tickerQueue <- Queue.unbounded[Ticker]
      stream = ZStream.fromQueue(tickerQueue)
      asyncEffect = for {
        p <- doRequest(request)
        r <- p.await
        tickers <- ZIO.fromOption(r.results).mapError(_ => new RuntimeException("No tickers found"))
        off <- tickerQueue.offerAll(tickers)
        _ <- pageRequest(r.next_url, tickerQueue)
      } yield ()
      _ <- asyncEffect.fork
    } yield stream
  }

  def getTickerDetails(symbol: String): ZIO[Any, Throwable, TickerDetails] = {
    val request = TickerDetailsRequest(symbol)
    val result = for{
      promise <- doRequest[TickerDetails](request)
      f <- promise.await.fork
      r <- ZIO.fromFiber(f)
      dopt <- r.asZIO
      d <- ZIO.fromOption(dopt).mapError(_ => new RuntimeException("No details found"))
    } yield d
    result
  }

  def getGroupedDaily(date: LocalDate): ZIO[Any, Throwable, Seq[Aggregate]] = {
    val request = GroupedDailyRequest(date.toString)
    val result = for {
      promise <- doRequest(request)
      f <- promise.await.fork
      z <- ZIO.fromFiber(f)
      r <- z.asZIO
    } yield r.getOrElse(Seq.empty)
    result
  }

  def getAggregates(ticker: String, fromDate: LocalDate): ZIO[Any, Throwable, Seq[Aggregate]] = {
    val request = AggregatesRequest(stocksTicker = ticker, from = fromDate.toString, to = LocalDate.now().toString)
    for {
      promise <- doRequest(request)
      r <- promise.await.map(_.results.getOrElse(Seq.empty))
    } yield r
  }

  def getSplits(from: LocalDate, to: LocalDate): Task[Seq[SplitData]] = {
    val request = SplitsRequest(from.toString, to.toString, 1000)
    for {
      promise <- doRequest(request)
      r <- promise.await.map(_.results.getOrElse(Seq.empty))
    } yield r
  }

  def getReverseSplits(from: LocalDate, to: LocalDate): Task[Seq[SplitData]] = {
    val request = SplitsRequest(from.toString, to.toString, 1000, true)
    for {
      promise <- doRequest(request)
      r <- promise.await.map(_.results.getOrElse(Seq.empty))
    } yield r
  }
}
