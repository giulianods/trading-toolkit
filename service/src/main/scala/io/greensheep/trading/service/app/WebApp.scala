package io.greensheep.trading.service.app

import zio._
import zio.http._

object WebApp {
  // Serve static files from the "dist" directory
  val staticFiles: HttpApp[Any] = HttpApp.fromResource("/dist")

  // Serve the index.html for unmatched routes
  val fallbackToIndex: HttpApp[Any] = Http.collectHttp[Request] {
    case Method.GET -> _ => Http.fromResource("/dist/index.html")
  }

  // Combine static file server and fallback for SPA routing
  val app: HttpApp[Any] = staticFiles ++ fallbackToIndex
}
