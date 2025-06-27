package io.greensheep.trading.core.types

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.LocalDate


object Watchlist {

  implicit val Encoder: JsonEncoder[Watchlist] = DeriveJsonEncoder.gen[Watchlist]

  implicit val Decoder: JsonDecoder[Watchlist] = DeriveJsonDecoder.gen[Watchlist]

}

case class Watchlist(
                      day: LocalDate,
                      screenName: String,
                      tickers: Seq[String]
                       ) {

  lazy val uuid: String = screenName + "-" + day
}
