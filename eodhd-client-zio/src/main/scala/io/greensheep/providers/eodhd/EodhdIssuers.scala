package io.greensheep.providers.eodhd

import java.util.Locale
import scala.annotation.tailrec
import scala.collection.mutable

object EodhdIssuers {
  def byTicker(shares: Seq[EodhdShare]): Map[String, String] = {
    val tickerByKey = mutable.Map.empty[String, String]
    val parent = mutable.Map.empty[String, String]

    @tailrec
    def root(ticker: String): String = parent.get(ticker) match {
      case Some(next) => root(next)
      case None => ticker
    }

    // Link by both identifiers so a listing without an ISIN can still match by name.
    shares.foreach { share =>
      val keys = issuerId(share.isin).toSeq ++ issuerName(share.name).toSeq
      keys.foreach { key =>
        val first = root(tickerByKey.getOrElseUpdate(key, share.ticker))
        val current = root(share.ticker)
        if (first != current) parent.update(current, first)
      }
    }

    shares.groupBy(share => root(share.ticker)).iterator
      .filter(_._2.size > 1)
      .flatMap { case (issuer, listings) => listings.map(share => share.ticker -> issuer) }
      .toMap
  }

  private def issuerId(isin: Option[String]): Option[String] =
    isin.map(_.trim.toUpperCase(Locale.ROOT))
      .filter(_.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]"))
      .map { id =>
        // Canadian and U.S. ISINs embed a CUSIP whose first six characters identify the issuer.
        val issuer = if (id.startsWith("CA") || id.startsWith("US")) id.take(8) else id
        s"id:$issuer"
      }

  private def issuerName(name: String): Option[String] = {
    val normalized = name.toLowerCase(Locale.ROOT)
      .replaceAll("[^\\p{L}\\p{N}]+", " ").trim
      .replaceAll(" (?:class [a-z0-9]+(?: common stock| shares)?|(?:subordinate|multiple|restricted|non) voting shares|common (?:shares|stock))$", "")
    Option.when(normalized.nonEmpty)(s"name:$normalized")
  }
}
