package io.greensheep.providers.bvb

import io.greensheep.providers.bvb.datatypes.BvbInstrument

import scala.util.matching.Regex

object SharesPageParser {

  private val Isin: Regex = """[A-Z]{2}[A-Z0-9]{9}[0-9]""".r
  private val QueryTicker: Regex = """[?&]s=([A-Z0-9.]+)""".r
  private val TickerIsin: Regex = """\b([A-Z][A-Z0-9.]{0,15})\s+([A-Z]{2}[A-Z0-9]{9}[0-9])\b""".r
  private val AllowedCategories: Set[String] = Set("Premium", "Standard", "Int'l", "Intl", "International")

  def parse(html: String): Seq[BvbInstrument] = {
    val fromLinks = parseLinkRows(html)
    val instruments = if (fromLinks.nonEmpty) fromLinks else parsePlainText(html)
    instruments
      .groupBy(_.ticker)
      .values
      .map(_.head)
      .toSeq
      .sortBy(_.ticker)
  }

  private def parseLinkRows(html: String): Seq[BvbInstrument] = {
    val lower = html
    val rowPattern = "(?is)<tr[^>]*>(.*?)</tr>".r
    rowPattern.findAllMatchIn(lower).flatMap { rowMatch =>
      val row = rowMatch.group(1)
      val ticker = QueryTicker.findFirstMatchIn(row).map(_.group(1).toUpperCase)
      val isin = Isin.findFirstIn(row.replaceAll("&nbsp;", " ").toUpperCase)
      val cells = "(?is)<t[dh][^>]*>(.*?)</t[dh]>".r
        .findAllMatchIn(row)
        .map(m => stripTags(m.group(1)).trim)
        .filter(_.nonEmpty)
        .toSeq
      val name = cells.lift(1).getOrElse("")
      val category = cells.lastOption.filter(AllowedCategories.contains).getOrElse {
        cells.find(AllowedCategories.contains).getOrElse("")
      }
      (ticker, isin) match {
        case (Some(sym), Some(code)) if sym.nonEmpty =>
          Some(BvbInstrument(sym, code, name, category))
        case _ =>
          None
      }
    }.toSeq
  }

  private def parsePlainText(html: String): Seq[BvbInstrument] = {
    val text = stripTags(html).replaceAll("&nbsp;", " ")
    TickerIsin.findAllMatchIn(text).map { m =>
      BvbInstrument(m.group(1), m.group(2), "", "")
    }.toSeq
  }

  private def stripTags(value: String): String =
    value
      .replaceAll("(?is)<script.*?>.*?</script>", " ")
      .replaceAll("(?is)<style.*?>.*?</style>", " ")
      .replaceAll("(?is)<br\\s*/?>", " ")
      .replaceAll("(?is)<[^>]+>", " ")
      .replaceAll("&amp;", "&")
      .replaceAll("\\s+", " ")
      .trim
}
