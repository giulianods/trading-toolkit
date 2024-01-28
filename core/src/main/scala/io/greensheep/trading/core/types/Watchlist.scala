package io.greensheep.trading.core.types

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}


object Watchlist {

  implicit val Encoder: JsonEncoder[Watchlist] = DeriveJsonEncoder.gen[Watchlist]

  implicit val Decoder: JsonDecoder[Watchlist] = DeriveJsonDecoder.gen[Watchlist]

}

case class Watchlist(
                       day: String,
                       screenName: String,
                       dataSource: String,
                       tickers: Seq[String]
                       ) {

  lazy val uuid = day + "-" + screenName + "-" + dataSource
}
