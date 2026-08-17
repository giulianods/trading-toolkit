import React, { useEffect, useRef } from 'react';
import { createChart } from 'lightweight-charts';

/**
 * OHLC bar chart for one ticker. Uses ~150 days of data.
 * Efficient for many instances (lightweight-charts is canvas-based).
 */
function OhlcChart({ ticker, quotes, height = 300, focused }) {
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
    chart.timeScale().fitContent();

    chartRef.current = chart;
    return () => {
      chart.remove();
      chartRef.current = null;
    };
  }, [ticker, quotes, height]);

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
