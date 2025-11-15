package io.greensheep.trading.api

import io.github.zeal18.zio.mongodb.driver.MongoDatabase
import io.greensheep.trading.api.ChartServices.{CacheKey, FinancialChartCacheKey}
import io.greensheep.trading.dataservice.MongoDataRepo
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.{CategoryAxis, DateAxis, NumberAxis}
import org.jfree.chart.plot.{CategoryPlot, CombinedDomainXYPlot, ValueMarker, XYPlot}
import org.jfree.chart.renderer.category.LineAndShapeRenderer
import org.jfree.chart.renderer.xy.{CandlestickRenderer, XYBarRenderer, XYLineAndShapeRenderer}
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.data.time.{Day, TimeSeries, TimeSeriesCollection}
import org.jfree.data.xy.DefaultHighLowDataset
import org.jfree.svg.SVGGraphics2D
import zio.*
import zio.cache.{Cache, Lookup}

import java.awt.{BasicStroke, Color, Font}
import org.jfree.chart.block.BlockBorder
import org.jfree.chart.ui.RectangleInsets
import java.awt.geom.Rectangle2D
import java.time.{LocalDate, ZoneId}
import java.util.Date

object ChartServices {

  sealed trait FinancialChartCategory {
    def label: String
    def paramValue: String
  }

  object FinancialChartCategory {
    case object Annual extends FinancialChartCategory {
      val label = "Annual"
      val paramValue = "annual"
    }
    case object Quarterly extends FinancialChartCategory {
      val label = "Quarterly"
      val paramValue = "quarterly"
    }

    val defaults: Set[FinancialChartCategory] = Set(Annual, Quarterly)

    def fromString(value: String): Option[FinancialChartCategory] =
      value.toLowerCase match {
        case "annual" => Some(Annual)
        case "quarterly" => Some(Quarterly)
        case _ => None
      }
  }

  final case class CacheKey(ticker: String, days: Int, width: Int, height: Int, highlightDay: Option[LocalDate])
  final case class FinancialChartCacheKey(
      ticker: String,
      category: FinancialChartCategory,
      limit: Int,
      width: Int,
      height: Int
  )

  private val cacheTtl = 8.hours
  
  private lazy val chartCache: URIO[MongoDatabase, Cache[CacheKey, Throwable, String]] = Cache.make(
    capacity = 10000,
    timeToLive = cacheTtl,
    lookup = Lookup(lookup = (key: CacheKey) => renderChart(key.ticker, key.days, key.width, key.height, key.highlightDay))
  )

  private lazy val financialChartCache: URIO[MongoDatabase, Cache[FinancialChartCacheKey, Throwable, String]] = Cache.make(
    capacity = 2000,
    timeToLive = cacheTtl,
    lookup = Lookup(lookup = (key: FinancialChartCacheKey) =>
      renderFinancialChart(key.ticker, key.category, key.limit, key.width, key.height)
    )
  )

  def buildLayer: ZLayer[MongoDatabase, Nothing, ChartServices] =
    ZLayer.scoped {
      for {
        priceCache <- chartCache
        financialCache <- financialChartCache
      } yield new ChartServices(priceCache, financialCache)
    }

  private def renderChart(ticker: String, days: Int, width: Int, height: Int, highlightDay: Option[LocalDate]): ZIO[MongoDatabase, Throwable, String] =
    for {
      data <- MongoDataRepo.getPriceData(ticker)
      ordered = data.quotes.sortBy(_.day.toEpochDay).takeRight(days)
      _ <- ZIO.cond(ordered.nonEmpty, (), new NoSuchElementException(s"No price data for ticker $ticker"))
      zone = ZoneId.systemDefault()
      dates = ordered.map(q => Date.from(q.day.atStartOfDay(zone).toInstant)).toArray
      opens = ordered.map(_.o).toArray
      highs = ordered.map(_.h).toArray
      lows = ordered.map(_.l).toArray
      closes = ordered.map(_.c).toArray
      volumes = ordered.map(q => q.v.toDouble).toArray
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
        val avg = window.sum / window.length
        maSeries.add(new Day(dates(idx)), avg)
      }
      maData = new TimeSeriesCollection()
      _ = maData.addSeries(maSeries)

      // Price plot with candlesticks and moving average
      priceAxis = new NumberAxis("Price")
      _ = priceAxis.setAutoRangeIncludesZero(false)
      candleR = new CandlestickRenderer()
      pricePlot = new XYPlot(ohlcData, null, priceAxis, candleR)
      maRenderer = new XYLineAndShapeRenderer(true, false)
      _ = pricePlot.setDataset(1, maData)
      _ = pricePlot.setRenderer(1, maRenderer)

      // Volume subplot
      volumeAxis = new NumberAxis("Volume")
      volumeR = new XYBarRenderer()
      _ = volumeR.setShadowVisible(false)
      volumePlot = new XYPlot(volumeData, null, volumeAxis, volumeR)

      highlightMillis = highlightDay.flatMap { day =>
        ordered.find(_.day == day).map { _ =>
          day.atStartOfDay(zone).toInstant.toEpochMilli.toDouble
        }
      }
      _ = highlightMillis.foreach { millis =>
        val priceMarker = new ValueMarker(millis)
        priceMarker.setPaint(Color.RED)
        priceMarker.setStroke(new BasicStroke(1.5f))
        pricePlot.addDomainMarker(priceMarker)

        val volumeMarker = new ValueMarker(millis)
        volumeMarker.setPaint(Color.RED)
        volumeMarker.setStroke(new BasicStroke(1.5f))
        volumePlot.addDomainMarker(volumeMarker)
      }

      // Combine plots sharing the same date axis
      dateAxis = new DateAxis("Date")
      _ = dateAxis.setTimeline(SegmentedTimeline.newMondayThroughFridayTimeline())
      combined = new CombinedDomainXYPlot(dateAxis)
      _ = combined.add(pricePlot, 20)
      _ = combined.add(volumePlot, 3)
      _ = combined.setGap(10.0)

      chart = new JFreeChart(ticker, JFreeChart.DEFAULT_TITLE_FONT, combined, false)
      g2 = new SVGGraphics2D(width, height)
      _ = chart.draw(g2, new Rectangle2D.Double(0, 0, width, height))
    } yield g2.getSVGElement()

  private def renderFinancialChart(
      ticker: String,
      category: FinancialChartCategory,
      limit: Int,
      width: Int,
      height: Int
  ): ZIO[MongoDatabase, Throwable, String] = {
    final case class FinancialPoint(
        label: String,
        revenuePerShare: Double,
        basicEarningsPerShare: Double,
        operatingMarginPct: Double,
        netIncomeMarginPct: Double
    )

    for {
      data <- MongoDataRepo.getFinancials(ticker)
      ordered = category match {
        case FinancialChartCategory.Annual =>
          data.annual.sortBy(_.year).takeRight(limit).map(entry =>
            FinancialPoint(
              label = entry.year.toString,
              revenuePerShare = entry.revenuePerShare,
              basicEarningsPerShare = entry.basicEarningsPerShare,
              operatingMarginPct = entry.operatingMargin * 100.0,
              netIncomeMarginPct = entry.netIncomeMargin * 100.0
            )
          )
        case FinancialChartCategory.Quarterly =>
          data.quarterly.sortBy(q => (q.year, q.quarter)).takeRight(limit).map(entry =>
            FinancialPoint(
              label = s"${entry.year} Q${entry.quarter}",
              revenuePerShare = entry.revenuePerShare,
              basicEarningsPerShare = entry.basicEarningsPerShare,
              operatingMarginPct = entry.operatingMargin * 100.0,
              netIncomeMarginPct = entry.netIncomeMargin * 100.0
            )
          )
      }
      _ <- ZIO.cond(ordered.nonEmpty, (), new NoSuchElementException(s"No ${category.label.toLowerCase} financials for ticker $ticker"))
      currencyDataset = new DefaultCategoryDataset()
      marginDataset = new DefaultCategoryDataset()
      _ = ordered.foreach { point =>
        currencyDataset.addValue(point.revenuePerShare, "Revenue / Share", point.label)
        currencyDataset.addValue(point.basicEarningsPerShare, "Basic EPS", point.label)
        marginDataset.addValue(point.operatingMarginPct, "Operating Margin %", point.label)
        marginDataset.addValue(point.netIncomeMarginPct, "Net Income Margin %", point.label)
      }
      domainAxis = new CategoryAxis("Year")
      currencyAxis = new NumberAxis("$ per share")
      _ = currencyAxis.setAutoRangeIncludesZero(false)
      currencyRenderer = new LineAndShapeRenderer(true, true)
      _ = currencyRenderer.setSeriesPaint(0, new Color(36, 192, 111))
      _ = currencyRenderer.setSeriesPaint(1, new Color(86, 182, 255))
      _ = currencyRenderer.setSeriesStroke(0, new BasicStroke(2.0f))
      _ = currencyRenderer.setSeriesStroke(1, new BasicStroke(2.0f))
      marginAxis = new NumberAxis("Margin %")
      marginRenderer = new LineAndShapeRenderer(true, true)
      _ = marginRenderer.setSeriesPaint(0, new Color(255, 193, 7))
      _ = marginRenderer.setSeriesPaint(1, new Color(255, 99, 132))
      _ = marginRenderer.setSeriesStroke(0, new BasicStroke(2.0f))
      _ = marginRenderer.setSeriesStroke(1, new BasicStroke(2.0f))
      plot = new CategoryPlot(currencyDataset, domainAxis, currencyAxis, currencyRenderer)
      _ = plot.setDataset(1, marginDataset)
      _ = plot.setRenderer(1, marginRenderer)
      _ = plot.setRangeAxis(1, marginAxis)
      _ = plot.mapDatasetToRangeAxis(1, 1)
      _ = plot.setBackgroundPaint(Color.BLACK)
      _ = plot.setDomainGridlinesVisible(true)
      _ = plot.setRangeGridlinesVisible(true)
      _ = plot.setRangeGridlinePaint(Color.DARK_GRAY)
      chart = new JFreeChart(s"$ticker ${category.label} Financials", JFreeChart.DEFAULT_TITLE_FONT, plot, true)
      _ = chart.setBackgroundPaint(Color.BLACK)
      val legend = Option(chart.getLegend)
      _ = legend.foreach { lg =>
        lg.setItemFont(new Font(Font.SANS_SERIF, Font.PLAIN, 7))
        lg.setItemLabelPadding(new RectangleInsets(1.0, 2.0, 1.0, 2.0))
        lg.setLegendItemGraphicPadding(new RectangleInsets(1.0, 2.0, 1.0, 2.0))
        lg.setPadding(RectangleInsets.ZERO_INSETS)
        lg.setBorder(BlockBorder.NONE)
      }
      g2 = new SVGGraphics2D(width, height)
      _ = chart.draw(g2, new Rectangle2D.Double(0, 0, width, height))
    } yield g2.getSVGElement()
  }
}

class ChartServices(
    priceChartCache: Cache[CacheKey, Throwable, String],
    financialChartCache: Cache[FinancialChartCacheKey, Throwable, String]
) {

  /**
   * Generate a candlestick chart with 50 day moving average and volume bars in SVG format for the given ticker.
   *
   * @param ticker stock ticker symbol
   * @param days number of days to display in the chart
   * @param width width of the resulting svg in pixels
   * @param height height of the resulting svg in pixels
   * @return svg representation of the chart
   */
  def chartSvg(ticker: String, days: Int, width: Int = 800, height: Int = 400, highlightDay: Option[LocalDate] = None): ZIO[MongoDatabase, Throwable, String] = {
    val key = CacheKey(ticker, days, width, height, highlightDay)
    priceChartCache.get(key)
  }

  def financialChartSvg(
      ticker: String,
      category: ChartServices.FinancialChartCategory,
      limit: Int = 5,
      width: Int = 320,
      height: Int = 200
  ): ZIO[MongoDatabase, Throwable, String] = {
    val sanitizedLimit = math.max(1, math.min(limit, 5))
    val sanitizedWidth = math.max(120, math.min(width, 800))
    val sanitizedHeight = math.max(100, math.min(height, 600))
    val key = FinancialChartCacheKey(ticker, category, sanitizedLimit, sanitizedWidth, sanitizedHeight)
    financialChartCache.get(key)
  }
}

