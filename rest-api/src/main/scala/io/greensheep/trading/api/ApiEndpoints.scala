package io.greensheep.trading.api
import io.greensheep.trading.core.types.Watchlist
import zio.*
import zio.http.{Response, *}
import io.greensheep.trading.dataservice.MongoDbDataRepo
import zio.json.EncoderOps
object ApiEndpoints {

  val mongoRepo = MongoDbDataRepo.build()

  def watchlistApi() = {
    ZIO.attempt(mongoRepo.getWatchlist("2024-01-28", "SEPA")).mapBoth(
      th => {
        th.printStackTrace()
        Response.internalServerError(th.getMessage)
      },
      wl =>
        Response.json(wl.toJson)
    )
  }

}
