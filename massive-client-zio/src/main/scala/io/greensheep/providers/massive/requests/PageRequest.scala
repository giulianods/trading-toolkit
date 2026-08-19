package io.greensheep.providers.massive.requests
import io.greensheep.providers.massive.datatypes.ApiResponse
import zio.ZIO
import zio.http.*
import zio.json.*

case class PageRequest[T](url: URL)(implicit val decoder: JsonDecoder[T]) extends ApiRequest[T] {
  def perform(): ZIO[Client, Throwable, ApiResponse[T]] = {
    for {
      resultString <- MassiveHttp.get(url)
      decoded = resultString.fromJson[ApiResponse[T]]
      apiResponse <- ZIO.fromEither(decoded.left.map(RuntimeException(_)))
      _ <- ZIO.fail(new RuntimeException(apiResponse.errorMessage)).unless(apiResponse.isValid)
    } yield apiResponse
  }
}
