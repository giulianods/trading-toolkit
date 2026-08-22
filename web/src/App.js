import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { DEFAULT_MARKET_ID, MARKETS, fetchWatchlist, marketById } from './api';
import ChartGrid from './components/ChartGrid';
import { SelectionContext } from './context/SelectionContext';
import './App.css';

const DEFAULT_WATCHLIST = 'SEPA';
const MARKET_STORAGE_KEY = 'ttk.selectedMarket';

function readStoredMarketId() {
  try {
    const stored = window.localStorage.getItem(MARKET_STORAGE_KEY);
    if (stored && MARKETS.some((m) => m.id === stored)) return stored;
  } catch (_) {
    /* ignore */
  }
  return DEFAULT_MARKET_ID;
}

function formatDateForFilename(dayStr) {
  if (dayStr) {
    const [y, m, d] = dayStr.split('-');
    return `${y.slice(-2)}-${m}-${d}`;
  }
  const d = new Date();
  const y = d.getFullYear().toString().slice(-2);
  const m = (d.getMonth() + 1).toString().padStart(2, '0');
  const day = d.getDate().toString().padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function App() {
  const [marketId, setMarketId] = useState(readStoredMarketId);
  const market = useMemo(() => marketById(marketId), [marketId]);
  const [watchlist, setWatchlist] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [selectedTickers, setSelectedTickers] = useState(() => new Set());

  const handleMarketChange = useCallback((nextId) => {
    if (nextId === marketId) return;
    setMarketId(nextId);
    setSelectedTickers(new Set());
    try {
      window.localStorage.setItem(MARKET_STORAGE_KEY, nextId);
    } catch (_) {
      /* ignore */
    }
  }, [marketId]);

  const handleSelectionChange = useCallback((ticker, checked) => {
    setSelectedTickers((prev) => {
      const next = new Set(prev);
      if (checked) next.add(ticker);
      else next.delete(ticker);
      return next;
    });
  }, []);

  const handleDownloadWatchlist = useCallback(() => {
    if (selectedTickers.size === 0) return;
    const watchlistName = watchlist?.screenName ?? DEFAULT_WATCHLIST;
    const dateStr = formatDateForFilename(watchlist?.day);
    const filename = `${market.label}-${watchlistName}-${dateStr}.csv`;
    const tickersInOrder = (watchlist?.tickers ?? []).filter((t) => selectedTickers.has(t));
    const content = tickersInOrder.join(',\n');
    const blob = new Blob([content], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }, [selectedTickers, watchlist?.screenName, watchlist?.day, watchlist?.tickers, market.label]);

  const loadWatchlist = useCallback(async (name = DEFAULT_WATCHLIST) => {
    setLoading(true);
    setError(null);
    setWatchlist(null);
    try {
      const data = await fetchWatchlist(market, name);
      setWatchlist(data);
    } catch (e) {
      setError(e.message);
      setWatchlist(null);
    } finally {
      setLoading(false);
    }
  }, [market]);

  useEffect(() => {
    loadWatchlist();
  }, [loadWatchlist]);

  const tickers = watchlist?.tickers ?? [];
  const hasSelection = selectedTickers.size > 0;

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-logo">
          <img src="/green-sheep.png" alt="Greensheep" className="app-logo" />
        </div>
        <div className="app-header-center">
          <div className="market-switcher" role="tablist" aria-label="Market">
            {MARKETS.map((m) => (
              <button
                key={m.id}
                type="button"
                role="tab"
                aria-selected={m.id === market.id}
                title={m.title}
                className={`market-switcher-btn${m.id === market.id ? ' market-switcher-btn--active' : ''}`}
                onClick={() => handleMarketChange(m.id)}
              >
                {m.label}
              </button>
            ))}
          </div>
          {loading && !watchlist && !error && (
            <p className="watchlist-info">Loading {market.title}…</p>
          )}
          {error && (
            <p className="app-error">{error}</p>
          )}
          {watchlist && !error && (
            <p className="watchlist-info">
              <span className="market-title">{market.title}</span>
              {' · '}
              Watchlist: <strong>{watchlist.screenName ?? DEFAULT_WATCHLIST}</strong>
              {watchlist.day && ` (${watchlist.day})`} — {tickers.length} tickers
            </p>
          )}
        </div>
        <div className="app-header-toolbox">
          {hasSelection && (
            <button
              type="button"
              className="toolbox-download-btn"
              onClick={handleDownloadWatchlist}
            >
              Download ({selectedTickers.size}) .csv
            </button>
          )}
        </div>
      </header>
      <main className="app-main">
        {error ? (
          <div className="chart-grid-empty">
            No watchlist for this market yet. Try another market or wait for screener init.
          </div>
        ) : loading && !watchlist ? (
          <div className="chart-grid-loading">Loading watchlist…</div>
        ) : (
          <SelectionContext.Provider
            value={{ selectedTickers, onSelectionChange: handleSelectionChange }}
          >
            <ChartGrid key={market.id} market={market} tickers={tickers} />
          </SelectionContext.Provider>
        )}
      </main>
    </div>
  );
}

export default App;
