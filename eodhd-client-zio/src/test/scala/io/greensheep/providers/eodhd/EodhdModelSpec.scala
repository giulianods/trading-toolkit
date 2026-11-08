package io.greensheep.providers.eodhd

import zio.json.*
import zio.test.*

import java.time.LocalDate

object EodhdModelSpec extends ZIOSpecDefault {
  import EodhdClient.*

  override def spec = suite("EODHD")(
    test("uses exchange-qualified tickers for CAD common stock only") {
      val rows = """[
        {"Code":"ABC-A","Name":"Example Inc","Currency":"CAD","Type":"Common Stock","Isin":"CA0000001011"},
        {"Code":"ABC-U","Name":"Example Inc","Currency":"USD","Type":"Common Stock"},
        {"Code":"ETF","Name":"A fund","Currency":"CAD","Type":"ETF","Isin":null}
      ]""".fromJson[Seq[Listing]].toOption.get
      assertTrue(
        commonShares("TO", rows) == Seq(EodhdShare("ABC-A.TO", "Example Inc", "TO", Some("CA0000001011"))),
        commonShares("V", rows).map(_.ticker) == Seq("ABC-A.V")
      )
    },
    test("groups different share classes and listings with missing ISINs by issuer") {
      val groups = EodhdIssuers.byTicker(Seq(
        EodhdShare("ABC-A.TO", "Example Inc. Class A", "TO", Some("CA0000001011")),
        EodhdShare("ABC-B.TO", "Example Telecom", "TO", Some("CA0000002019")),
        EodhdShare("ABC.V", "Example Inc", "V", None),
        EodhdShare("XYZ.V", "Another Company", "V", None)
      ))
      assertTrue(groups.size == 3, groups.values.toSet.size == 1, !groups.contains("XYZ.V"))
    },
    test("applies split ratios to earlier OHLC and keeps the adjusted volume from EODHD") {
      val splitDay = LocalDate.parse("2026-01-05")
      val bars = Seq(
        Bar(splitDay, Some(20), Some(24), Some(18), Some(22), Some(1000)),
        Bar(splitDay.minusDays(3), Some(40), Some(48), Some(36), Some(44), Some(2000)),
        Bar(splitDay.minusDays(4), None, None, None, None, Some(0))
      )
      val quotes = toQuotes(bars, Seq(Split(splitDay, "2.000000/1.000000"))).toOption.get
      assertTrue(
        quotes.map(_.day) == Seq(splitDay.minusDays(3), splitDay),
        quotes.map(q => (q.o, q.h, q.l, q.c)) == Seq((20.0, 24.0, 18.0, 22.0), (20.0, 24.0, 18.0, 22.0)),
        quotes.map(_.v) == Seq(2000, 1000),
        toQuotes(bars, Seq(Split(splitDay, "1/0"))).isLeft
      )
    }
  )
}
