package io.greensheep.providers.polygon.datatypes.financials

import zio.json._

object DataPointLong {
  implicit val Decoder: JsonDecoder[DataPointLong] =
    DeriveJsonDecoder.gen[DataPointLong]
}


@jsonMemberNames(SnakeCase)
case class DataPointLong(
                    value: Long,
                    unit: String,
                    label: String,
                    order: Int
                    )
