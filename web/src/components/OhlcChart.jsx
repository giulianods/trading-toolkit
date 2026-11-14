import React, { useEffect, useRef } from 'react';
import { createChart, LineStyle, LineType } from 'lightweight-charts';

function baseCups(points, quotes) {
  const quoteIndices = new Map(quotes.map((quote, index) => [quote.day, index]));
  const anchors = points.map(point => {
    if (!Array.isArray(point) || !quoteIndices.has(point[0]) || !Number.isFinite(point[1])) return null;
    const index = quoteIndices.get(point[0]);
    return { index, price: point[1] };
  });
  const dips = [];
  for (let i = 1; i + 1 < anchors.length; i += 2) {
    const [left, low, right] = anchors.slice(i - 1, i + 2);
    if (!left || !low || !right || !(left.index < low.index && low.index < right.index)) continue;
    const depth = Math.max(0, Math.min(left.price, right.price) - low.price);
    dips.push({ left, low, right, strength: depth * Math.sqrt(right.index - left.index) });
  }
  if (dips.length <= 2) return dips;

  // Merge smaller dips into the nearest broad cup. A fourth needs to be nearly
  // as prominent as the deepest, longest part of the base.
  const ranked = [...dips].sort((a, b) => b.strength - a.strength);
  const strongest = ranked[0].strength;
  const count = ranked[3]?.strength > strongest * 0.75 ? 4
    : ranked[2].strength > strongest * 0.45 ? 3 : 2;
  const major = ranked.slice(0, count).sort((a, b) => a.low.index - b.low.index);
  const rims = [dips[0].left];
  for (let i = 1; i < major.length; i++) {
    const between = dips.slice(dips.indexOf(major[i - 1]), dips.indexOf(major[i]));
    rims.push(between.reduce((highest, dip) => dip.right.price > highest.price ? dip.right : highest, between[0].right));
  }
  rims.push(dips.at(-1).right);
  return major.map(({ low }, i) => ({ left: rims[i], low, right: rims[i + 1] }));
}

function cupArm(quotes, lowIndex, rimIndex, bottom) {
  const direction = Math.sign(rimIndex - lowIndex);
  const span = Math.abs(rimIndex - lowIndex);
  const rim = quotes[rimIndex].l;
  // Compare a few smooth shapes so a late recovery can have a flatter floor.
  const shapes = [1, 2, 4, 8].map(power => {
    const points = Array.from({ length: span + 1 }, (_, distance) => {
      const index = lowIndex + direction * distance;
      const eased = (1 - Math.cos(Math.PI * distance / span)) / 2;
      return { index, price: bottom + (rim - bottom) * eased ** power };
    });
    const differences = points.map(({ index, price }) => price - quotes[index].l);
    const overlap = Math.max(0, ...differences);
    const average = differences.reduce((sum, difference) => sum + difference, 0) / points.length;
    return { points, overlap, gap: overlap - average };
  });
  return shapes.reduce((best, shape) => shape.gap < best.gap ? shape : best);
}

function cupCurve({ left, low, right }, quotes, clearance) {
  const bottom = Math.min(quotes[left.index].l, quotes[low.index].l, quotes[right.index].l);
  const descent = cupArm(quotes, low.index, left.index, bottom);
  const recovery = cupArm(quotes, low.index, right.index, bottom);
  const offset = Math.max(descent.overlap, recovery.overlap) + clearance;
  // Shift the entire arc by the worst overlap. Moving individual points down
  // would make the curve retrace each candle's lower wick.
  return descent.points.slice().reverse().concat(recovery.points.slice(1))
    .map(({ index, price }) => ({ time: quotes[index].day, value: price - offset }));
}

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

  const priceRange = Math.max(...quotes.map(q => q.h)) - Math.min(...quotes.map(q => q.l));
  const clearance = Math.max(priceRange * 0.05, quotes.at(-1).c * 0.0025);
  for (const cup of baseCups(points, quotes)) {
    chart.addLineSeries({
      priceScaleId: 'right',
      color: '#c4b5fd',
      lineWidth: 2,
      lineType: LineType.Curved,
      priceLineVisible: false,
      lastValueVisible: false,
      crosshairMarkerVisible: false,
    }).setData(cupCurve(cup, quotes, clearance));
  }
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
