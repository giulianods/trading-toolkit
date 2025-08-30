package io.greensheep.trading.dataservice

import io.github.zeal18.zio.mongodb.bson.annotations.BsonId
import org.bson.{BsonReader, BsonWriter}
import org.bson.codecs.BsonCodec
import zio.json.ast.Json
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

import java.time.LocalDate
import java.util.TimeZone

object StockUniverse {

  implicit val Encoder: JsonEncoder[StockUniverse] = DeriveJsonEncoder.gen[StockUniverse]

  implicit val Decoder: JsonDecoder[StockUniverse] =
    DeriveJsonDecoder.gen[StockUniverse].orElse(
      JsonDecoder[Json.Obj].map { obj =>
        StockUniverse(
          _id = obj.get("_id").flatMap(_.as[String].toOption).getOrElse(""),
          name = obj.get("name").flatMap(_.as[String].toOption).getOrElse(""),
          dataSource = obj.get("dataSource").flatMap(_.as[String].toOption).getOrElse(""),
          lastUpdateQuotes = obj.get("lastUpdateQuotes").flatMap(_.as[LocalDate].toOption).getOrElse(LocalDate.of(1970,1,1)),
          timeZone = obj.get("timeZone").flatMap(_.as[String].toOption).getOrElse("UTC"),
          symbols = obj.get("symbols").flatMap(_.as[Seq[String]].toOption).getOrElse(Seq.empty),
          lastUpdateSplits = obj.get("lastUpdateSplits").flatMap(_.as[LocalDate].toOption).getOrElse(LocalDate.of(1970,1,1))
        )
      }
    )

}

case class StockUniverse(
                        @BsonId _id: String,
                        name: String,
                        dataSource: String,
                        lastUpdateQuotes: LocalDate,
                        timeZone: String,
                        symbols: Seq[String],
                        lastUpdateSplits: LocalDate = LocalDate.of(1970,1,1)
                        ) {


  def getTimezone: TimeZone = TimeZone.getTimeZone(timeZone)
}
