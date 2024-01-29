package io.greensheep.trading.api

import io.greensheep.trading.dataservice.MongoDbDataRepo
import zio.*
import zio.http.*
object Main extends ZIOAppDefault{


  private def getSymbols(): ZIO[Any, Throwable, Seq[String]] = {
    ZIO.succeed(Seq("a", "b", "c"))(Trace.empty)
  }
  private val app: HttpApp[Any] = {
    val symbolsZIO = MongoDbDataRepo.build().getSymbols.map(_.mkString("\n"))
    val resp = symbolsZIO.mapBoth(e => {
      e.printStackTrace()
      Response.internalServerError(e.getMessage)
    },x => Response.text(x))
    Routes(
      // This line works fine
      Method.GET / "symbols" -> Handler.fromZIO(ZIO.succeed(Seq("a", "b", "c")).map(r => Response.text(r.mkString("\n")))),

      Method.GET / "symbols2" -> Handler.fromZIO(resp),
      Method.GET / "watchlist" -> Handler.fromZIO(ApiEndpoints.watchlistApi()),
    ).toHttpApp
  }
  override val run =
    Server.serve(app).provide(Server.default)
}
