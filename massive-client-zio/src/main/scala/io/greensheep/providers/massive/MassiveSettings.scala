package io.greensheep.providers.massive


import zio.*
import zio.Schedule.WithState
import zio.http.{Headers, QueryParams, URL}

import scala.language.postfixOps


object MassiveSettings {

  val DefaultRequestsPerMinute: Int = 5

  val DefaultBaseUrl: String = "https://api.massive.com"

  lazy val ApiKey: String = {
    java.lang.System.getenv("MASSIVE_API_KEY") match {
      case null => throw new Exception("MASSIVE_API_KEY environment variable not set")
      case key => key
    }
  }

  def defaultSettings: MassiveSettings = MassiveSettings(
    DefaultRequestsPerMinute,
    DefaultBaseUrl,
    ApiKey
  )

}
case class MassiveSettings(
  requestsPerMinute: Int = DefaultRequestsPerMinute,
  baseUrl: String = DefaultBaseUrl,
  apiKey: String = ApiKey) {

  val requestTimeAllowance: Duration = (60 / requestsPerMinute + 2).seconds

  val schedule: WithState[Long, Any, Any, Long] = Schedule.spaced(requestTimeAllowance)

  val headers: Headers = Headers.empty

  def authenticate(url: URL): URL =
    url.addQueryParams(QueryParams("apiKey" -> apiKey))
}
