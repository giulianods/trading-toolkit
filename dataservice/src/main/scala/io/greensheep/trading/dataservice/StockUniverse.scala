package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.bson.annotations.BsonId
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.LocalDate
import java.util.TimeZone

object StockUniverse {

  implicit val Encoder: JsonEncoder[StockUniverse] = DeriveJsonEncoder.gen[StockUniverse]

  implicit val Decoder: JsonDecoder[StockUniverse] = DeriveJsonDecoder.gen[StockUniverse]
}

case class StockUniverse(
                        @BsonId _id: String,
                        name: String,
                        dataSource: String,
                        lastUpdateQuotes: LocalDate,
                        timeZone: String,
                        symbols: Seq[String]
                        ) {


  def getTimezone: TimeZone = TimeZone.getTimeZone(timeZone)
}
