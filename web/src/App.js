import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { DEFAULT_MARKET_ID, fetchMarkets, fetchMarketDataStatus, fetchScreens, fetchWatchlist, marketById } from './api';
import ChartGrid from './components/ChartGrid';
import SectionMenu from './components/SectionMenu';
import Reports from './components/Reports';
import Portfolio from './components/Portfolio';
import { SelectionContext } from './context/SelectionContext';
import './App.css';

const MARKET_STORAGE_KEY = 'ttk.selectedMarket';

function readStoredMarketId() {
  try {
    const stored = window.localStorage.getItem(MARKET_STORAGE_KEY);
    if (stored) return stored;
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
  const [section, setSection] = useState('Screens');
  const [menuOpen, setMenuOpen] = useState(false);
  const closeMenu = useCallback(() => setMenuOpen(false), []);
  const [marketId, setMarketId] = useState(readStoredMarketId);
  const [profiles, setProfiles] = useState([]);
  const [profileError, setProfileError] = useState(null);
  const market = useMemo(() => marketById(marketId, profiles), [marketId, profiles]);
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
  }, [selectedTickers, watchlist, selectedScreen, market?.label]);

  useEffect(() => {
    let active = true;
    fetchMarkets().then((data) => {
      if (!data.length) throw new Error('No market profiles are configured.');
      if (active) {
        setProfiles(data);
        setMarketId((id) => marketById(id, data).id);
      }
    }).catch((e) => { if (active) setProfileError(e.message); });
    return () => { active = false; };
  }, []);

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
    if (!selectedScreen || !market) return;
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
    if (!market) return undefined;
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

  if (!market) return <div className="chart-grid-empty">{profileError || 'Loading market profiles…'}</div>;

  return (
    <div className="app">
      <header className="app-header">
        <button type="button" className="app-header-logo" aria-label="Open navigation menu" aria-expanded={menuOpen} aria-controls="section-menu" onClick={() => setMenuOpen(true)}>
          <img src="/green-sheep.png" alt="Greensheep" className="app-logo" />
        </button>
        <div className="app-header-center">
          <div className="app-header-controls">
            <div className="market-switcher" role="tablist" aria-label="Market">
              {profiles.map((m) => (
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
                {profiles.map(({ id, label }) => <option key={id} value={id}>{label}</option>)}
              </select>
            </label>
            {section === 'Screens' ? <label className="screen-picker">
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
            </label> : <strong className="current-section">{section}</strong>}
          </div>
          {section === 'Screens' && (screenError || error) && (
            <p className="app-error">{screenError || error}</p>
          )}
          {section === 'Screens' && <div className="watchlist-info watchlist-meta">
            <span>
              Latest market data: {latestQuoteDate ?? (latestQuoteDate === undefined ? 'Loading…' : 'Unavailable')}
            </span>
            {watchlist && !error && (
              <>
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
              </>
            )}
          </div>}
        </div>
        <div className="app-header-toolbox">
          {section === 'Screens' && hasSelection && (
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
      <main className={`app-main${section !== 'Screens' ? ' app-main-section' : ''}`}>
        {section === 'Reports' ? <Reports key={market.id} market={market} /> : section === 'Portfolio' ? <Portfolio key={market.id} market={market} /> : screenError ? (
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
      {menuOpen && <SectionMenu section={section} onSelect={setSection} onClose={closeMenu} />}
    </div>
  );
}

export default App;
