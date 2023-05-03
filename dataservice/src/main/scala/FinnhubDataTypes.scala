package org.greensheep.tradingtk.dataservice

import zio.json.{DeriveJsonDecoder, JsonDecoder}

import java.time.LocalDate


object CompanyProfile{
  implicit val CompanyProfileDecoder: JsonDecoder[CompanyProfile] = DeriveJsonDecoder.gen[CompanyProfile]

}

case class CompanyProfile(
                         name: String,
                         ipo: LocalDate,
                         marketCapitalization: Long,
                         ticker: String
                         )


object FinnhubDataTypes {

}
