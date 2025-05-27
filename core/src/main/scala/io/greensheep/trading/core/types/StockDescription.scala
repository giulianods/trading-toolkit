package io.greensheep.trading.core.types

import zio.Chunk
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder, jsonExclude}

import java.time.LocalDate

object StockDescription {

  implicit val Encoder: JsonEncoder[StockDescription] = DeriveJsonEncoder.gen[StockDescription]

  implicit val Decoder: JsonDecoder[StockDescription] = DeriveJsonDecoder.gen[StockDescription]

  
}

case class StockDescription(
                             symbol: String,
                             companyName: String,
                             ipoDate: LocalDate,
                             primaryExchange: String = "",
                             description: String = "",
                             totalEmployees: Int = 0,
                             sicCode: String = "",
                             sicDescription: String = "",
                             marketCap: Long = 0,
                             weightedSharesOutstanding: Long = 0
                           )
