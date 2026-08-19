package io.greensheep.providers.massive.datatypes

import zio.{Task, ZIO}
import zio.json.{DeriveJsonDecoder, JsonDecoder}

case class ApiResponse[T](
                           next_url: Option[String],
                           results: Option[T],
                           status: String,
                           error: Option[String]
                         ) {

  val isLast: Boolean = next_url.isEmpty

  val isValid: Boolean = (status == "OK" || status == "DELAYED")

  lazy val errorMessage: String = error.getOrElse("Unspecified error")

  def asZIO: Task[Option[T]] = if(isValid){
    ZIO.succeed(results)
  }
  else {
    ZIO.fail(new RuntimeException(errorMessage))
  }
}

object ApiResponse {

  implicit def apiResponseDecoder[T](implicit dec: JsonDecoder[T]): JsonDecoder[ApiResponse[T]] =
    DeriveJsonDecoder.gen[ApiResponse[T]]
}



