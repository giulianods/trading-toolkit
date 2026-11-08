import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { FixedSizeGrid as Grid } from 'react-window';
import OhlcChart from './OhlcChart';
import FinancialCharts from './FinancialCharts';
import { useSelection } from '../context/SelectionContext';
import { fetchDescriptions, fetchQuotes } from '../api';

const COLUMNS = 3;
const CELL_GAP = 12;
const CHART_HEIGHT = 300;
const TICKER_HEADER_HEIGHT = 44;
const OVERSCAN_ROWS = 2;
const FINANCIAL_CARD_WIDTH = 300;

function ChartCell({ ticker, label, quotes, index, focusIndex, setFocusedIndex, pendingCheckRef, financialMarket, stacked }) {
  const { selectedTickers, onSelectionChange } = useSelection();
  const focused = index === focusIndex;
  const checked = selectedTickers.has(ticker);
  const companyName = label?.companyName?.trim() ?? '';
  const sector = label?.sector?.trim() ?? '';
  const headerTitle = [companyName, sector].filter(Boolean).join(' · ');
  const selectionLabel = companyName ? `${ticker}, ${companyName}` : ticker;
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
    >
      <div
        className="chart-ticker"
        title={headerTitle || undefined}
        onClick={(e) => e.stopPropagation()}
        onDoubleClick={(e) => e.stopPropagation()}
      >
        <input
          className="chart-ticker-checkbox"
          type="checkbox"
          checked={checked}
          onChange={(e) => onSelectionChange(ticker, e.target.checked)}
          aria-label={`Include ${selectionLabel} in watchlist`}
        />
        <span className="chart-ticker-symbol">{ticker}</span>
        {(companyName || sector) && (
          <span className="chart-ticker-details">
            {companyName && <span className="chart-ticker-name">{companyName}</span>}
            {sector && <span className="chart-ticker-sector">{sector}</span>}
          </span>
        )}
      </div>
      <div className={financialMarket && !stacked ? 'chart-content chart-content--side-by-side' : 'chart-content'}>
        <OhlcChart ticker={ticker} quotes={quotes} height={CHART_HEIGHT} focused={focused} />
        {financialMarket && <FinancialCharts market={financialMarket} ticker={ticker} />}
      </div>
    </div>
  );
}

function ChartGridInner({ market, tickers }) {
  const [quotesByTicker, setQuotesByTicker] = useState({});
  const [labelsByTicker, setLabelsByTicker] = useState({});
  const [loading, setLoading] = useState(true);
  const [focusedIndex, setFocusedIndex] = useState(0);
  const [size, setSize] = useState(() => ({ width: window.innerWidth, height: window.innerHeight }));
  const viewportRef = useRef(null);
  const gridRef = useRef(null);
  const pendingCheckRef = useRef(null);

  const tickerKey = tickers.join(',');
  const count = tickers.length;
  const financialMarket = market.country === 'us' || market.country === 'ro' ? market : null;
  const viewportColumns = size.width < 600 ? 1 : size.width < 960 ? 2 : COLUMNS;
  const cols = Math.min(viewportColumns, Math.max(1, count));
  const rows = Math.ceil(count / cols) || 1;
  const focusIndex = Math.max(0, Math.min(focusedIndex, count - 1));

  const loadQuotes = useCallback(async () => {
    if (!tickerKey) {
      setQuotesByTicker({});
      setLoading(false);
      return;
    }
    setLoading(true);
    const results = {};
    await Promise.all(
      tickerKey.split(',').map(async (ticker) => {
        try {
          const q = await fetchQuotes(market, ticker);
          results[ticker] = q;
        } catch (e) {
          results[ticker] = [];
        }
      })
    );
    setQuotesByTicker(results);
    setLoading(false);
  }, [market, tickerKey]);

  useEffect(() => {
    loadQuotes();
  }, [loadQuotes]);

  useEffect(() => {
    let cancelled = false;
    const symbols = tickerKey ? tickerKey.split(',') : [];
    if (!symbols.length) {
      setLabelsByTicker({});
      return undefined;
    }
    fetchDescriptions(market, symbols)
      .then((labels) => {
        if (!cancelled) setLabelsByTicker(labels);
      })
      .catch(() => {
        if (!cancelled) setLabelsByTicker({});
      });
    return () => {
      cancelled = true;
    };
  }, [market, tickerKey]);

  useEffect(() => {
    setFocusedIndex(0);
  }, [market.id]);

  useEffect(() => {
    return () => {
      if (pendingCheckRef.current) window.clearTimeout(pendingCheckRef.current);
    };
  }, []);

  useLayoutEffect(() => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const update = () => {
      const { clientWidth: width, clientHeight: height } = viewport;
      if (width > 0) {
        setSize((previous) => previous.width === width && previous.height === height ? previous : { width, height });
      }
    };
    update();
    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(update);
      observer.observe(viewport);
      return () => observer.disconnect();
    }
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, [loading, count]);

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
  const stacked = columnWidth < FINANCIAL_CARD_WIDTH;
  const rowHeight = TICKER_HEADER_HEIGHT + CHART_HEIGHT + CELL_GAP + (financialMarket && stacked ? CHART_HEIGHT : 0);

  const cellRenderer = useCallback(
    ({ columnIndex, rowIndex, style }) => {
      const index = rowIndex * cols + columnIndex;
      if (index >= tickers.length) return <div key={`empty-${rowIndex}-${columnIndex}`} style={style} />;
      const ticker = tickers[index];
      const quotes = quotesByTicker[ticker] || [];
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
            label={labelsByTicker[ticker]}
            quotes={quotes}
            index={index}
            focusIndex={focusIndex}
            setFocusedIndex={setFocusedIndex}
            pendingCheckRef={pendingCheckRef}
            financialMarket={financialMarket}
            stacked={stacked}
          />
        </div>
      );
    },
    [tickers, quotesByTicker, labelsByTicker, focusIndex, cols, financialMarket, stacked]
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
      <div className="chart-grid-viewport" ref={viewportRef}>
        <Grid
          ref={gridRef}
          columnCount={cols}
          columnWidth={columnWidth}
          height={size.height}
          rowCount={rows}
          rowHeight={rowHeight}
          width={size.width}
          overscanRowCount={OVERSCAN_ROWS}
          className="chart-grid-virtual"
        >
          {cellRenderer}
        </Grid>
      </div>
    </div>
  );
}

const ChartGrid = React.memo(function ChartGrid({ market, tickers }) {
  return <ChartGridInner market={market} tickers={tickers} />;
});

export default ChartGrid;
