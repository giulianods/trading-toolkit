package io.greensheep.providers.xetra

import io.greensheep.providers.xetra.datatypes.XetraInstrument

import java.io.StringReader

object XetraCsvParser {

  private val HeaderPrefix = "Product Status"

  def parse(csv: String): Seq[XetraInstrument] = {
    val lines = csv.linesIterator.toSeq
    val start = lines.indexWhere(_.startsWith(HeaderPrefix))
    if (start < 0) Nil
    else {
      val body = lines.drop(start).mkString("\n")
      val rows = splitCsv(body)
      if (rows.isEmpty) Nil
      else {
        val header = rows.head
        val index = header.zipWithIndex.toMap
        def col(name: String, row: Array[String]): String =
          index.get(name).flatMap(i => row.lift(i)).getOrElse("").trim

        rows.tail.flatMap { row =>
          val instrumentType = col("Instrument Type", row)
          val segment = col("Market Segment", row)
          val isin = col("ISIN", row)
          val ticker = col("Mnemonic", row).toUpperCase
          val name = col("Instrument", row)
          val isShare = instrumentType.equalsIgnoreCase("CS")
          val isGerman = isin.startsWith("DE")
          val allowedSegment =
            XetraSettings.AllowedGermanSegments.contains(segment) &&
              !XetraSettings.ScaleSegments.contains(segment)
          if (isShare && isGerman && allowedSegment && ticker.nonEmpty && isin.nonEmpty)
            Some(XetraInstrument(ticker, isin, name, segment))
          else None
        }
      }
    }
  }

  /** Minimal RFC4180-ish parser for semicolon-separated T7 files. */
  private def splitCsv(text: String): List[Array[String]] = {
    val reader = new StringReader(text)
    val buffer = new scala.collection.mutable.ListBuffer[Array[String]]
    val field = new StringBuilder
    val row = new scala.collection.mutable.ListBuffer[String]
    var inQuotes = false
    var ch = reader.read()
    while (ch != -1) {
      ch.toChar match {
        case '"' =>
          inQuotes = !inQuotes
        case ';' if !inQuotes =>
          row += field.toString()
          field.clear()
        case '\n' if !inQuotes =>
          row += field.toString()
          field.clear()
          if (row.nonEmpty) buffer += row.toArray
          row.clear()
        case '\r' =>
          ()
        case c =>
          field.append(c)
      }
      ch = reader.read()
    }
    if (field.nonEmpty || row.nonEmpty) {
      row += field.toString()
      buffer += row.toArray
    }
    buffer.toList
  }
}
