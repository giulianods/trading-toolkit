// Relative paths: dev server proxies to API (see setupProxy.js); production can set REACT_APP_API_URL
const API_BASE = process.env.REACT_APP_API_URL || '';

const DEFAULT_CHART_DAYS = 150;
const MARKET_ROOT = '/markets/us/massive';

function apiPath(path, searchParams = {}) {
  const params = new URLSearchParams(searchParams).toString();
  return `${API_BASE}${path}${params ? `?${params}` : ''}`;
}

/**
 * Fetch a watchlist by name.
 * @param {string} name - Watchlist name (e.g. 'SEPA')
 * @param {string} [day] - Optional date (YYYY-MM-DD) for historical
 * @returns {Promise<{ day: string, screenName: string, tickers: string[] }>}
 */
export async function fetchWatchlist(name, day) {
  const url = apiPath(`${MARKET_ROOT}/watchlists/${encodeURIComponent(name)}`, day ? { day } : {});
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Watchlist ${name}: ${res.status}`);
  return res.json();
}

/**
 * Fetch OHLC quote history for a ticker.
 * @param {string} ticker - Symbol (e.g. 'AAPL')
 * @returns {Promise<Array<{ day: string, o: number, h: number, l: number, c: number, v: number }>>}
 */
export async function fetchQuotes(ticker) {
  const url = apiPath(`${MARKET_ROOT}/quotes/${encodeURIComponent(ticker)}`);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Quotes ${ticker}: ${res.status}`);
  const data = await res.json();
  const quotes = data.quotes || [];
  return quotes.slice(-DEFAULT_CHART_DAYS);
}

export { DEFAULT_CHART_DAYS };
