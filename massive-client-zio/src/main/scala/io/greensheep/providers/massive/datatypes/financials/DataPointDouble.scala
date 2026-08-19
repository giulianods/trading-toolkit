package io.greensheep.providers.massive.datatypes.financials

import zio.json.{DeriveJsonDecoder, JsonDecoder, SnakeCase, jsonMemberNames}

object DataPointDouble {
  implicit val Decoder: JsonDecoder[DataPointDouble] =
    DeriveJsonDecoder.gen[DataPointDouble]
}


@jsonMemberNames(SnakeCase)
case class DataPointDouble(
                          value: Double,
                          unit: String,
                          label: String,
                          order: Int
                        )