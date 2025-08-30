package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.dataservice.MongoDataRepo
import zio.*

object ChartServices {

  /**
   * Generate a simple line chart in SVG format using closing prices for the given ticker.
   *
   * @param ticker stock ticker symbol
   * @param width width of the resulting svg in pixels
   * @param height height of the resulting svg in pixels
   * @return svg representation of the chart
   */
  def chartSvg(ticker: String, width: Int = 800, height: Int = 400): ZIO[MongoDatabase, Throwable, String] = {
    for {
      data <- MongoDataRepo.getPriceData(ticker)
      ordered = data.quotes.sortBy(_.day.toEpochDay)
      closes = ordered.map(_.c)
      _ <- ZIO.cond(closes.nonEmpty, (), new NoSuchElementException(s"No price data for ticker $ticker"))
      min = closes.min
      max = closes.max
      range = if (max - min == 0) 1 else max - min
      scaleY = height / range
      scaleX = if (ordered.size <= 1) width.toDouble else width.toDouble / (ordered.size - 1)
      points = closes.zipWithIndex.map { case (c, idx) =>
        val x = idx * scaleX
        val y = height - ((c - min) * scaleY)
        f"$x%.2f,$y%.2f"
      }.mkString(" ")
      svg = s"<svg viewBox='0 0 $width $height' xmlns='http://www.w3.org/2000/svg'><polyline fill='none' stroke='#0074d9' stroke-width='2' points='$points'/></svg>"
    } yield svg
  }
}

