package io.greensheep.providers.massive

import io.greensheep.providers.massive.datatypes.{ApiResponse, Ticker}
import zio.http.*
import zio.json.*
import zio.stream.ZStream
import zio.{Chunk, Schedule, ZIO}

import scala.util.{Failure, Success}

class PaginatedRequestExecutor[T](urlString: String, settings: MassiveSettings)
                                 (implicit val decoder: JsonDecoder[T]) {
  // Legacy pagination helper used by ReferenceClient; live ingest uses MassiveAsyncApi.

  private val nextUrl = new java.util.concurrent.atomic.AtomicReference[String](urlString)

  private val headers: Headers = settings.headers

  private val schedule = Schedule.spaced(settings.requestTimeAllowance)

  private val client = Client.default

  private def responseFromUrl(): ZIO[Any, Throwable, ApiResponse[Seq[T]]] = {
    val r = ZIO.scoped {
      for {
        res <- Client.request(Request(
          headers = headers,
          url = settings.authenticate(URL.decode(nextUrl.get()).toOption.get),
          method = Method.GET,
        ))
        resultString <- res.body.asString
        apiResponse <- ZIO.fromTry(resultString.fromJson[ApiResponse[Seq[T]]]
          .fold(l => Failure(Exception(l)), Success(_)))

        _ = nextUrl.set(apiResponse.next_url.getOrElse(""))
      } yield apiResponse
    }
    r.provideLayer(Client.default)
  }

  val resultPages: ZStream[Any, Throwable, Seq[T]] = {
    ZStream.unfoldChunkZIO(false) {
      case true =>
        ZIO.none // We already hit the last page
      case false => // We did not hit the last page yet
        for {
          page <- responseFromUrl()
        } yield Some(Chunk.single(page.results.getOrElse(Seq.empty)), page.isLast)
    }.schedule(schedule)
  }

  val results: ZStream[Any, Throwable, T] = {
    resultPages.flatMap(seq => ZStream.fromIterable(seq))
  }

  val resultsStream: ZStream[Any, Throwable, T] = {
    ZStream.unfoldChunkZIO(false) {
      case true =>
        ZIO.none // We already hit the last page
      case false => // We did not hit the last page yet
        for {
          page <- responseFromUrl()
        } yield Some(Chunk.fromIterable(page.results.getOrElse(Seq.empty)), page.isLast)
    }
  }
}
