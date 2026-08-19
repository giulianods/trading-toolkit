package io.greensheep.providers.massive.datatypes

import zio.json.{DeriveJsonDecoder, JsonDecoder, SnakeCase, jsonField, jsonMemberNames}

object Ticker {
  implicit val TickerDecoder: JsonDecoder[Ticker] = DeriveJsonDecoder.gen[Ticker]
}

@jsonMemberNames(SnakeCase)
case class Ticker(ticker: String,
                  name: String,
                  lastUpdatedUtc: String,
                  market: String,
                  locale: String,
                  @jsonField("type") _type: String

                 ) {

  override def toString: String = ticker + "\t\t" + name + "\t\t" + lastUpdatedUtc + "\t\t" + market + "\t\t" + _type
}
