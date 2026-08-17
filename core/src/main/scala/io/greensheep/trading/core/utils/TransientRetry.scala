package io.greensheep.trading.core.utils

import zio.*

object TransientRetry {

  def isTransient(error: Throwable): Boolean = {
    val message = Option(error.getMessage).getOrElse("").toLowerCase
    val status = statusCode(error)
    val retryableStatus = status.contains(429) || status.exists(code => code >= 500 && code < 600)
    val retryableMessage =
      message.contains("timeout") ||
        message.contains("timed out") ||
        message.contains("connection") ||
        message.contains("429") ||
        message.contains("503") ||
        message.contains("502") ||
        message.contains("504")
    val io = error.isInstanceOf[java.io.IOException] || error.isInstanceOf[java.net.SocketTimeoutException]
    retryableStatus || retryableMessage || io || Option(error.getCause).exists(isTransient)
  }

  def statusCode(error: Throwable): Option[Int] =
    error match {
      case HttpStatusException(code, _) => Some(code)
      case _ =>
        val message = Option(error.getMessage).getOrElse("")
        "HTTP (\\d{3})".r.findFirstMatchIn(message).map(_.group(1).toInt)
          .orElse("\\b(\\d{3})\\b".r.findFirstMatchIn(message).map(_.group(1).toInt).filter(code => code >= 400 && code < 600))
    }

  def isForbiddenToday(error: Throwable): Boolean =
    statusCode(error).contains(403)

  val http: Schedule[Any, Throwable, Any] =
    Schedule.recurWhile[Throwable](err => isTransient(err) && !isForbiddenToday(err)) &&
      Schedule.recurs(4) &&
      Schedule.exponential(400.millis)

  val mongo: Schedule[Any, Throwable, Any] =
    Schedule.recurWhile[Throwable](isTransient) && Schedule.recurs(2) && Schedule.exponential(200.millis)
}

final case class HttpStatusException(code: Int, body: String)
    extends RuntimeException(s"HTTP $code: ${body.take(500)}")
