package io.greensheep.providers.massive.datatypes

import zio.json.{DeriveJsonDecoder, JsonDecoder, SnakeCase, jsonField, jsonMemberNames}

object TickerDetails {
  implicit val Decoder: JsonDecoder[TickerDetails] = DeriveJsonDecoder.gen[TickerDetails]
}

@jsonMemberNames(SnakeCase)
case class TickerDetails(
                        ticker: String,
                        name: String,
                        marketCap: Option[BigDecimal],
                        market: String,
                        primaryExchange: String,
                        description: Option[String],
                        totalEmployees: Option[Int],
                        sicCode: Option[String],
                        sicDescription: Option[String],
                        @jsonField("type") _type: String,
                        weightedSharesOutstanding: Option[Long],
                        listDate: Option[String],
                        ) {

}
