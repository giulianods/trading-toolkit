package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.bson.annotations.BsonId
import io.greensheep.trading.core.types.Stock
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.LocalDate

object StockUniverse {

  implicit val Encoder: JsonEncoder[StockUniverse] = DeriveJsonEncoder.gen[StockUniverse]

  implicit val Decoder: JsonDecoder[StockUniverse] = DeriveJsonDecoder.gen[StockUniverse]
}

case class StockUniverse(
                        @BsonId id: String,
                        name: String,
                        lastUpdateQuotes: LocalDate,
                        stocks: Seq[String]
                        )
