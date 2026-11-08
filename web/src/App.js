import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { DEFAULT_MARKET_ID, MARKETS, fetchMarketDataStatus, fetchScreens, fetchWatchlist, marketById } from './api';
import ChartGrid from './components/ChartGrid';
import { SelectionContext } from './context/SelectionContext';
import './App.css';

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
  const [screens, setScreens] = useState([]);
  const [screenId, setScreenId] = useState('');
  const [screenError, setScreenError] = useState(null);
  const selectedScreen = screens.find((screen) => screen.id === screenId) ?? screens[0];
  const [watchlist, setWatchlist] = useState(null);
  const [latestQuoteDate, setLatestQuoteDate] = useState(undefined);
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
    if (selectedTickers.size === 0 || !watchlist) return;
    const watchlistName = watchlist.screenName ?? selectedScreen.id;
    const dateStr = formatDateForFilename(watchlist.day);
    const filename = `${market.label}-${watchlistName}-${dateStr}.csv`;
    const tickersInOrder = watchlist.tickers.filter((t) => selectedTickers.has(t));
    const content = tickersInOrder.join(',\n');
    const blob = new Blob([content], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }, [selectedTickers, watchlist, selectedScreen, market.label]);

  useEffect(() => {
    let active = true;
    fetchScreens()
      .then((options) => {
        if (!options.length) throw new Error('No screens are configured.');
        if (active) setScreens(options);
      })
      .catch((e) => { if (active) setScreenError(e.message); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!selectedScreen) return;
    let active = true;
    setLoading(true);
    setError(null);
    setWatchlist(null);
    fetchWatchlist(market, selectedScreen.id)
      .then((data) => { if (active) setWatchlist(data); })
      .catch((e) => { if (active) setError(e.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [market, selectedScreen]);

  useEffect(() => {
    let active = true;
    setLatestQuoteDate(undefined);
    fetchMarketDataStatus(market)
      .then((data) => { if (active) setLatestQuoteDate(data.latestQuoteDate ?? null); })
      .catch(() => { if (active) setLatestQuoteDate(null); });
    return () => { active = false; };
  }, [market]);

  const tickers = watchlist?.tickers ?? [];
  const vcpStatsByTicker = useMemo(() => {
    if (selectedScreen?.id !== 'VCP') return undefined;
    return new Map((watchlist?.stats ?? []).map(({ ticker, stats }) => [ticker, stats]));
  }, [selectedScreen?.id, watchlist]);
  const hasSelection = selectedTickers.size > 0;

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-logo">
          <img src="/green-sheep.png" alt="Greensheep" className="app-logo" />
        </div>
        <div className="app-header-center">
          <div className="app-header-controls">
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
            <label className="screen-picker mobile-market-picker">
              Market
              <select value={market.id} onChange={(event) => handleMarketChange(event.target.value)}>
                {MARKETS.map(({ id, label }) => <option key={id} value={id}>{label}</option>)}
              </select>
            </label>
            <label className="screen-picker">
              Screen
              <select
                value={selectedScreen?.id ?? ''}
                disabled={!selectedScreen}
                onChange={(event) => {
                  setScreenId(event.target.value);
                  setSelectedTickers(new Set());
                }}
              >
                {!selectedScreen && <option value="">{screenError ? 'Unavailable' : 'Loading…'}</option>}
                {screens.map(({ id, label }) => <option key={id} value={id}>{label}</option>)}
              </select>
            </label>
          </div>
          {(screenError || error) && (
            <p className="app-error">{screenError || error}</p>
          )}
          <p className="watchlist-info">
            Latest market data: {latestQuoteDate ?? (latestQuoteDate === undefined ? 'Loading…' : 'Unavailable')}
          </p>
          {watchlist && !error && (
            <p className="watchlist-info watchlist-meta">
              <span className="market-title">{market.title}</span>
              <span>
                Watchlist: <strong>{selectedScreen.label}</strong>
                {watchlist.day && ` (${watchlist.day})`}
              </span>
              <span>{tickers.length} tickers</span>
              <span className="watchlist-generated">
                Generated: {watchlist.generatedAt ? (
                  <time dateTime={watchlist.generatedAt}>
                    {new Date(watchlist.generatedAt).toLocaleString(undefined, { timeZoneName: 'short' })}
                  </time>
                ) : 'Unavailable'}
              </span>
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
        {screenError ? (
          <div className="chart-grid-empty">Screen options are unavailable.</div>
        ) : error ? (
          <div className="chart-grid-empty">
            No {selectedScreen.label} watchlist for this market yet. Try another screen or market.
          </div>
        ) : loading && !watchlist ? (
          <div className="chart-grid-loading">Loading watchlist…</div>
        ) : (
          <SelectionContext.Provider
            value={{ selectedTickers, onSelectionChange: handleSelectionChange }}
          >
            <ChartGrid
              key={`${market.id}/${selectedScreen.id}`}
              market={market}
              tickers={tickers}
              vcpStatsByTicker={vcpStatsByTicker}
            />
          </SelectionContext.Provider>
        )}
      </main>
    </div>
  );
}

export default App;
