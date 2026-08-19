package io.greensheep.providers.massive.datatypes

import zio.json.{DeriveJsonDecoder, JsonDecoder}

import java.time.{Instant, ZoneId, ZonedDateTime}

object Aggregate{

  implicit val Decoder: JsonDecoder[Aggregate] = DeriveJsonDecoder.gen[Aggregate]
}

case class Aggregate(
                    c: Double,
                    h: Double,
                    l: Double,
                    n: Option[Int] = None,
                    o: Double,
                    t: Long,
                    v: Double,
                    vw: Option[Double] = None,
                    T: Option[String] = None){

  lazy val timestamp: ZonedDateTime = {
    Instant.ofEpochMilli(t).atZone(ZoneId.of("UTC"))
  }

  lazy val numberOfShares: Int = n.getOrElse(0)

  lazy val wwap: Double = vw.getOrElse(0.0)

  override def toString: String = s"${T.getOrElse("?")} - $o - $h - $l - $c - $v - ${n.getOrElse(0)} - $timestamp"
}
