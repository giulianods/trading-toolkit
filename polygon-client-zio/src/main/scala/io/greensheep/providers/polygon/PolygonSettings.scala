package io.greensheep.providers.polygon


import zio.*
import zio.Schedule.WithState
import zio.http.{Headers, QueryParams, URL}

import scala.language.postfixOps


object PolygonSettings {

  val DefaultRequestsPerMinute: Int = 5

  val DefaultBaseUrl: String = "https://api.polygon.io"

  lazy val ApiKey: String = {
    java.lang.System.getenv("POLYGON_API_KEY") match {
      case null => throw new Exception("POLYGON_API_KEY environment variable not set")
      case key => key
    }
  }

  def defaultSettings: PolygonSettings = PolygonSettings(
    DefaultRequestsPerMinute,
    DefaultBaseUrl,
    ApiKey
  )

}
case class PolygonSettings(
  requestsPerMinute: Int = DefaultRequestsPerMinute,
  baseUrl: String = DefaultBaseUrl,
  apiKey: String = ApiKey) {

  val requestTimeAllowance: Duration = (60 / requestsPerMinute + 2).seconds

  val schedule: WithState[Long, Any, Any, Long] = Schedule.spaced(requestTimeAllowance)

  val headers: Headers = Headers.empty

  def authenticate(url: URL): URL =
    url.addQueryParams(QueryParams("apiKey" -> apiKey))
}
