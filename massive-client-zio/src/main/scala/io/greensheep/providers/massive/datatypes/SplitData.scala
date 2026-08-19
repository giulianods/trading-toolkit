package io.greensheep.providers.massive.datatypes

import zio.json.{DeriveJsonDecoder, JsonDecoder}


object SplitData {
  implicit val Decoder: JsonDecoder[SplitData] = DeriveJsonDecoder.gen[SplitData]
}

case class SplitData(
                      ticker: String,
                      execution_date: String,
                      split_from: Double,
                      split_to: Double
                    ) {

}
