package io.greensheep.providers.massive.datatypes

import zio.json.{DeriveJsonDecoder, JsonDecoder}

case class ApiResponseSeq[T] (
                               resultsCount: Option[Int],
                               status: String,
                               results: Option[Seq[T]],
                               error: Option[String],
                               message: Option[String]
                             ) {

  def getResults: Seq[T] = results.getOrElse(Seq.empty)

  def isValid: Boolean = status == "OK"

  def getResultsCount: Int = resultsCount.getOrElse(0)

  def getErrorMessage: String = {
    error.getOrElse("") + " " + message.getOrElse("unknown error")
  }
}

object ApiResponseSeq {
  implicit def Decoder[T](implicit dec: JsonDecoder[T]): JsonDecoder[ApiResponseSeq[T]] =
    DeriveJsonDecoder.gen[ApiResponseSeq[T]]
}
