package io.greensheep.trading.service.services

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.dataservice.MongoDataRepo
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.{DateAxis, NumberAxis}
import org.jfree.chart.plot.{CombinedDomainXYPlot, XYPlot}
import org.jfree.chart.renderer.xy.{CandlestickRenderer, XYBarRenderer, XYLineAndShapeRenderer}
import org.jfree.data.time.{Day, TimeSeries, TimeSeriesCollection}
import org.jfree.data.xy.DefaultHighLowDataset
import org.jfree.svg.SVGGraphics2D
import zio.*

import java.awt.geom.Rectangle2D
import java.time.ZoneId
import java.util.Date

object ChartServices {

  /**
   * Generate a candlestick chart with 50 day moving average and volume bars in SVG format for the given ticker.
   *
   * @param ticker stock ticker symbol
   * @param days number of days to display in the chart
   * @param width width of the resulting svg in pixels
   * @param height height of the resulting svg in pixels
   * @return svg representation of the chart
   */
  def chartSvg(ticker: String, days: Int, width: Int = 800, height: Int = 400): ZIO[MongoDatabase, Throwable, String] = {
    for {
      data    <- MongoDataRepo.getPriceData(ticker)
      ordered  = data.quotes.sortBy(_.day.toEpochDay).takeRight(days)
      _       <- ZIO.cond(ordered.nonEmpty, (), new NoSuchElementException(s"No price data for ticker $ticker"))
      zone     = ZoneId.systemDefault()
      dates    = ordered.map(q => Date.from(q.day.atStartOfDay(zone).toInstant)).toArray
      opens    = ordered.map(_.o).toArray
      highs    = ordered.map(_.h).toArray
      lows     = ordered.map(_.l).toArray
      closes   = ordered.map(_.c).toArray
      volumes  = ordered.map(q => q.v.toDouble).toArray
      ohlcData = DefaultHighLowDataset(ticker, dates, highs, lows, opens, closes, volumes)

      // Volume dataset
      volumeSeries = new TimeSeries("Volume")
      _ = ordered.foreach { q =>
        val d = Date.from(q.day.atStartOfDay(zone).toInstant)
        volumeSeries.add(new Day(d), q.v)
      }
      volumeData = new TimeSeriesCollection()
      _ = volumeData.addSeries(volumeSeries)

      // 50-day moving average
      maSeries = new TimeSeries("50 Day SMA")
      _ = closes.indices.drop(49).foreach { idx =>
        val window = closes.slice(idx - 49, idx + 1)
        val avg    = window.sum / window.length
        maSeries.add(new Day(dates(idx)), avg)
      }
      maData = new TimeSeriesCollection()
      _ = maData.addSeries(maSeries)

      // Price plot with candlesticks and moving average
      priceAxis    = new NumberAxis("Price")
      _            = priceAxis.setAutoRangeIncludesZero(false)
      candleR      = new CandlestickRenderer()
      pricePlot    = new XYPlot(ohlcData, null, priceAxis, candleR)
      maRenderer   = new XYLineAndShapeRenderer(true, false)
      _            = pricePlot.setDataset(1, maData)
      _            = pricePlot.setRenderer(1, maRenderer)

      // Volume subplot
      volumeAxis   = new NumberAxis("Volume")
      volumeR      = new XYBarRenderer()
      _            = volumeR.setShadowVisible(false)
      volumePlot   = new XYPlot(volumeData, null, volumeAxis, volumeR)

      // Combine plots sharing the same date axis
      dateAxis     = new DateAxis("Date")
      combined     = new CombinedDomainXYPlot(dateAxis)
      _            = combined.add(pricePlot, 4)
      _            = combined.add(volumePlot, 1)
      _            = combined.setGap(10.0)

      chart        = new JFreeChart(ticker, JFreeChart.DEFAULT_TITLE_FONT, combined, false)
      g2           = new SVGGraphics2D(width, height)
      _            = chart.draw(g2, new Rectangle2D.Double(0, 0, width, height))
    } yield g2.getSVGElement()
  }
}

