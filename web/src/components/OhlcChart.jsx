import React, { useEffect, useRef } from 'react';
import { createChart, LineStyle, LineType } from 'lightweight-charts';

function addVcpAnnotations(chart, candles, quotes, stats) {
  const levels = [
    { price: Number(stats.pivot), color: '#60a5fa', lineStyle: LineStyle.Dashed },
    { price: Number(stats.support), color: '#fbbf24', lineStyle: LineStyle.Dashed },
  ];
  levels.filter(({ price }) => Number.isFinite(price) && price > 0)
    .forEach(level => candles.createPriceLine({ ...level, axisLabelVisible: false }));

  if (!stats.swingPoints) return;
  let points;
  try {
    points = JSON.parse(stats.swingPoints);
  } catch (_) {
    return;
  }
  if (!Array.isArray(points)) return;

  const quoteIndices = new Map(quotes.map((quote, index) => [quote.day, index]));
  const anchors = points
    .filter(point => Array.isArray(point) && quoteIndices.has(point[0]) && Number.isFinite(point[1]))
    .map(([day, price]) => {
      const index = quoteIndices.get(day);
      return { index, price: Math.min(price, quotes[index].l) };
    });
  if (anchors.length < 2) return;

  const priceRange = Math.max(...quotes.map(q => q.h)) - Math.min(...quotes.map(q => q.l));
  const clearance = Math.max(priceRange * 0.05, quotes.at(-1).c * 0.0025);
  const curve = [];
  for (let i = 1; i < anchors.length; i++) {
    const start = anchors[i - 1];
    const end = anchors[i];
    for (let index = start.index; index < end.index; index++) {
      const progress = (index - start.index) / (end.index - start.index);
      const eased = (1 - Math.cos(Math.PI * progress)) / 2;
      const price = start.price + (end.price - start.price) * eased;
      const quote = quotes[index];
      // Keep the guide below the wick even when a session dips through the arc.
      curve.push({ time: quote.day, value: Math.min(price, quote.l) - clearance });
    }
  }
  const end = anchors.at(-1);
  curve.push({ time: quotes[end.index].day, value: end.price - clearance });

  chart.addLineSeries({
    priceScaleId: 'right',
    color: '#c4b5fd',
    lineWidth: 2,
    lineType: LineType.Curved,
    priceLineVisible: false,
    lastValueVisible: false,
    crosshairMarkerVisible: false,
  }).setData(curve);
}

/**
 * OHLC bar chart for one ticker. Uses ~150 days of data.
 * Efficient for many instances (lightweight-charts is canvas-based).
 */
function OhlcChart({ ticker, quotes, vcpStats, height = 300, focused }) {
  const containerRef = useRef(null);
  const chartRef = useRef(null);

  useEffect(() => {
    if (!containerRef.current || !quotes?.length) return;

    const chart = createChart(containerRef.current, {
      layout: {
        background: { color: '#1a1d21' },
        textColor: '#9ca3af',
        fontFamily: "'Segoe UI', system-ui, sans-serif",
      },
      grid: {
        vertLines: { color: '#2d3238' },
        horzLines: { color: '#2d3238' },
      },
      rightPriceScale: {
        borderColor: '#374151',
        scaleMargins: { top: 0.1, bottom: 0.15 },
      },
      timeScale: {
        borderColor: '#374151',
        timeVisible: true,
        secondsVisible: false,
        rightOffset: 5,
      },
      crosshair: { vertLine: { width: 1 }, horzLine: { width: 1 } },
      handleScroll: {
        mouseWheel: false,
        pressedMouseMove: false,
        horzTouchDrag: false,
        vertTouchDrag: false,
      },
      handleScale: {
        mouseWheel: false,
        pinch: false,
        axisPressedMouseMove: false,
        axisDoubleClickReset: false,
      },
      width: containerRef.current.clientWidth,
      height,
      autoSize: true,
    });

    const candleSeries = chart.addCandlestickSeries({
      upColor: '#22c55e',
      downColor: '#ef4444',
      borderDownColor: '#ef4444',
      borderUpColor: '#22c55e',
      wickDownColor: '#ef4444',
      wickUpColor: '#22c55e',
    });

    const data = quotes.map((q) => ({
      time: q.day,
      open: q.o,
      high: q.h,
      low: q.l,
      close: q.c,
    }));
    candleSeries.setData(data);
    if (vcpStats) addVcpAnnotations(chart, candleSeries, quotes, vcpStats);
    chart.timeScale().fitContent();

    chartRef.current = chart;
    return () => {
      chart.remove();
      chartRef.current = null;
    };
  }, [ticker, quotes, vcpStats, height]);

  return (
    <div
      ref={containerRef}
      className={`chart-cell ${focused ? 'chart-cell--focused' : ''}`}
      style={{ width: '100%', minHeight: height, pointerEvents: 'none' }}
      aria-hidden="true"
    />
  );
}

export default OhlcChart;
