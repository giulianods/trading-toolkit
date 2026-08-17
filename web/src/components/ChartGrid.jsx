import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { FixedSizeGrid as Grid } from 'react-window';
import OhlcChart from './OhlcChart';
import { useSelection } from '../context/SelectionContext';
import { fetchQuotes } from '../api';

const COLUMNS = 3;
const CELL_GAP = 12;
const CHART_HEIGHT = 300;
const TICKER_HEADER_HEIGHT = 44;
const ROW_HEIGHT = TICKER_HEADER_HEIGHT + CHART_HEIGHT + CELL_GAP;
const OVERSCAN_ROWS = 2;
const HEADER_HEIGHT = 72;
const WRAPPER_PADDING = 12;

function getViewportGridSize() {
  return {
    width: Math.max(400, window.innerWidth - WRAPPER_PADDING * 2),
    height: Math.max(400, window.innerHeight - HEADER_HEIGHT - WRAPPER_PADDING * 2),
  };
}

function ChartCell({ ticker, quotes, index, focusIndex, setFocusedIndex, pendingCheckRef }) {
  const { selectedTickers, onSelectionChange } = useSelection();
  const focused = index === focusIndex;
  const checked = selectedTickers.has(ticker);
  return (
    <div
      className={`chart-wrapper${checked ? ' chart-wrapper--checked' : ''}`}
      onClick={() => {
        if (pendingCheckRef.current) return;
        pendingCheckRef.current = window.setTimeout(() => {
          pendingCheckRef.current = null;
          onSelectionChange(ticker, !selectedTickers.has(ticker));
        }, 300);
      }}
      onDoubleClick={() => {
        if (pendingCheckRef.current) {
          window.clearTimeout(pendingCheckRef.current);
          pendingCheckRef.current = null;
        }
        setFocusedIndex(index);
      }}
      role="button"
      tabIndex={-1}
      aria-label={`Select ${ticker} for watchlist`}
    >
      <div className="chart-ticker" onClick={(e) => e.stopPropagation()}>
        <label className="chart-ticker-checkbox">
          <input
            type="checkbox"
            checked={selectedTickers.has(ticker)}
            onChange={(e) => onSelectionChange(ticker, e.target.checked)}
            onClick={(e) => e.stopPropagation()}
            aria-label={`Include ${ticker} in watchlist`}
          />
          <span className="chart-ticker-symbol">{ticker}</span>
        </label>
      </div>
      <OhlcChart
        ticker={ticker}
        quotes={quotes}
        height={CHART_HEIGHT}
        focused={focused}
      />
    </div>
  );
}

function ChartGridInner({ tickers }) {
  const [quotesByTicker, setQuotesByTicker] = useState({});
  const [loading, setLoading] = useState(true);
  const [focusedIndex, setFocusedIndex] = useState(0);
  const [size, setSize] = useState(getViewportGridSize);
  const gridRef = useRef(null);
  const pendingCheckRef = useRef(null);

  const count = tickers.length;
  const cols = Math.min(COLUMNS, Math.max(1, count));
  const rows = Math.ceil(count / cols) || 1;
  const focusIndex = Math.max(0, Math.min(focusedIndex, count - 1));

  const loadQuotes = useCallback(async () => {
    if (!tickers.length) {
      setLoading(false);
      return;
    }
    setLoading(true);
    const results = {};
    await Promise.all(
      tickers.map(async (ticker) => {
        try {
          const q = await fetchQuotes(ticker);
          results[ticker] = q;
        } catch (e) {
          results[ticker] = [];
        }
      })
    );
    setQuotesByTicker(results);
    setLoading(false);
  }, [tickers.join(',')]);

  useEffect(() => {
    loadQuotes();
  }, [loadQuotes]);

  useEffect(() => {
    return () => {
      if (pendingCheckRef.current) window.clearTimeout(pendingCheckRef.current);
    };
  }, []);

  useLayoutEffect(() => {
    const update = () => setSize(getViewportGridSize());
    update();
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, []);

  useEffect(() => {
    if (count === 0 || !gridRef.current) return;
    const row = Math.floor(focusIndex / cols);
    const col = focusIndex % cols;
    gridRef.current.scrollToItem({ rowIndex: row, columnIndex: col, align: 'auto' });
  }, [focusIndex, count, cols]);

  useEffect(() => {
    const onKeyDown = (e) => {
      if (count === 0) return;
      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setFocusedIndex((i) => Math.min(count - 1, i + cols));
          break;
        case 'ArrowUp':
          e.preventDefault();
          setFocusedIndex((i) => Math.max(0, i - cols));
          break;
        case 'ArrowRight':
          e.preventDefault();
          setFocusedIndex((i) => Math.min(count - 1, i + 1));
          break;
        case 'ArrowLeft':
          e.preventDefault();
          setFocusedIndex((i) => Math.max(0, i - 1));
          break;
        default:
          break;
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [count, cols]);

  const columnWidth = useMemo(() => {
    const totalGap = CELL_GAP * (cols - 1);
    return Math.floor((size.width - totalGap) / cols);
  }, [size.width, cols]);

  const cellRenderer = useCallback(
    ({ columnIndex, rowIndex, style }) => {
      const index = rowIndex * cols + columnIndex;
      if (index >= tickers.length) return <div key={`empty-${rowIndex}-${columnIndex}`} style={style} />;
      const ticker = tickers[index];
      const quotes = quotesByTicker[ticker] || [];
      const focused = index === focusIndex;
      const cellStyle = {
        ...style,
        paddingRight: CELL_GAP,
        paddingBottom: CELL_GAP,
        boxSizing: 'border-box',
      };
      return (
        <div key={ticker} style={cellStyle}>
          <ChartCell
            ticker={ticker}
            quotes={quotes}
            index={index}
            focusIndex={focusIndex}
            setFocusedIndex={setFocusedIndex}
            pendingCheckRef={pendingCheckRef}
          />
        </div>
      );
    },
    [tickers, quotesByTicker, focusIndex, cols]
  );

  if (loading) {
    return (
      <div className="chart-grid-loading">
        Loading quotes…
      </div>
    );
  }

  if (!tickers.length) {
    return (
      <div className="chart-grid-empty">
        No tickers in watchlist.
      </div>
    );
  }

  return (
    <div className="chart-grid-wrapper">
      <Grid
        ref={gridRef}
        columnCount={cols}
        columnWidth={columnWidth}
        height={size.height}
        rowCount={rows}
        rowHeight={ROW_HEIGHT}
        width={size.width}
        overscanRowCount={OVERSCAN_ROWS}
        className="chart-grid-virtual"
      >
        {cellRenderer}
      </Grid>
    </div>
  );
}

const ChartGrid = React.memo(function ChartGrid({ tickers }) {
  return <ChartGridInner tickers={tickers} />;
});

export default ChartGrid;
