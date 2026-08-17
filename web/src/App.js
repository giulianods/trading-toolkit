import React, { useCallback, useEffect, useState } from 'react';
import { fetchWatchlist } from './api';
import ChartGrid from './components/ChartGrid';
import { SelectionContext } from './context/SelectionContext';
import './App.css';

const DEFAULT_WATCHLIST = 'SEPA';

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
  const [watchlist, setWatchlist] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [selectedTickers, setSelectedTickers] = useState(() => new Set());

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
    const filename = `${watchlistName}-${dateStr}.csv`;
    const tickersInOrder = (watchlist?.tickers ?? []).filter((t) => selectedTickers.has(t));
    const content = tickersInOrder.join(',\n');
    const blob = new Blob([content], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }, [selectedTickers, watchlist?.screenName, watchlist?.day, watchlist?.tickers]);

  const loadWatchlist = useCallback(async (name = DEFAULT_WATCHLIST) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchWatchlist(name);
      setWatchlist(data);
    } catch (e) {
      setError(e.message);
      setWatchlist(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadWatchlist();
  }, [loadWatchlist]);

  if (loading && !watchlist) {
    return (
      <div className="app">
        <header className="app-header">
          <div className="app-header-logo">
            <img src="/green-sheep.png" alt="Greensheep" className="app-logo" />
          </div>
          <p>Loading watchlist…</p>
        </header>
      </div>
    );
  }

  if (error) {
    return (
      <div className="app">
        <header className="app-header">
          <div className="app-header-logo">
            <img src="/green-sheep.png" alt="Greensheep" className="app-logo" />
          </div>
          <p className="app-error">{error}</p>
          <p>Ensure the REST service is running at http://localhost:7777</p>
        </header>
      </div>
    );
  }

  const tickers = watchlist?.tickers ?? [];
  const hasSelection = selectedTickers.size > 0;

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-header-logo">
          <img src="/green-sheep.png" alt="Greensheep" className="app-logo" />
        </div>
        <div className="app-header-center">
          <p className="watchlist-info">
            Watchlist: <strong>{watchlist?.screenName ?? DEFAULT_WATCHLIST}</strong>
            {watchlist?.day && ` (${watchlist.day})`} — {tickers.length} tickers
          </p>
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
        <SelectionContext.Provider
          value={{ selectedTickers, onSelectionChange: handleSelectionChange }}
        >
          <ChartGrid tickers={tickers} />
        </SelectionContext.Provider>
      </main>
    </div>
  );
}

export default App;
