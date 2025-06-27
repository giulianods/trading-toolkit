package io.greensheep.trading.service.app

import zio.*
import zio.http.*

class RestApi {

  val app: HttpApp[Any] = Routes(
    Method.GET / "status" -> Handler.fromZIO(ZIO.succeed("running").map(x => Response.text(x)))).toHttpApp

  def start: ZIO[Any, Throwable, Unit] = Server.serve(app).provide(Server.default)
}
