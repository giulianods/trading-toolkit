// Relative paths: dev server proxies to API (see setupProxy.js); production can set REACT_APP_API_URL
const API_BASE = process.env.REACT_APP_API_URL || '';

const DEFAULT_CHART_DAYS = 150;

export const MARKETS = [
  { id: 'us-massive', country: 'us', provider: 'massive', label: 'US Massive', title: 'US · Massive' },
  { id: 'us-alpaca', country: 'us', provider: 'alpaca', label: 'US Alpaca', title: 'US · Alpaca' },
  { id: 'ro-bvb', country: 'ro', provider: 'bvb', label: 'RO', title: 'Romania · BVB' },
  { id: 'de-xetra', country: 'de', provider: 'xetra', label: 'DE', title: 'Germany · Xetra' },
  { id: 'jp-jquants', country: 'jp', provider: 'jquants', label: 'JP', title: 'Japan · J-Quants' },
  { id: 'ca-eodhd', country: 'ca', provider: 'eodhd', label: 'CA', title: 'Canada · EODHD' },
];

export const DEFAULT_MARKET_ID = 'us-massive';

export function marketById(id) {
  return MARKETS.find((m) => m.id === id) ?? MARKETS[0];
}

export function marketRoot(market) {
  return `/markets/${market.country}/${market.provider}`;
}

function apiPath(path, searchParams = {}) {
  const params = new URLSearchParams(searchParams).toString();
  return `${API_BASE}${path}${params ? `?${params}` : ''}`;
}

export async function fetchMarketDataStatus(market) {
  const res = await fetch(apiPath(`${marketRoot(market)}/data-status`));
  if (!res.ok) throw new Error(`Market data status: ${res.status}`);
  return res.json();
}

export async function fetchScreens() {
  const res = await fetch(apiPath('/markets/screens'));
  if (!res.ok) throw new Error(`Screen options: ${res.status}`);
  return res.json();
}

export async function fetchIncomeStatements(market, ticker, signal) {
  const res = await fetch(apiPath(`${marketRoot(market)}/income-statements/${encodeURIComponent(ticker)}`), { signal });
  if (!res.ok) throw new Error(`Income statements: ${res.status}`);
  return res.json();
}

/**
 * Fetch a watchlist by name for a market.
 * @param {{ country: string, provider: string }} market
 * @param {string} name - Watchlist name (e.g. 'SEPA')
 * @param {string} [day] - Optional date (YYYY-MM-DD) for historical
 */
export async function fetchWatchlist(market, name, day) {
  const url = apiPath(`${marketRoot(market)}/watchlists/${encodeURIComponent(name)}`, day ? { day } : {});
  const res = await fetch(url);
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    throw new Error(detail || `Watchlist ${name}: ${res.status}`);
  }
  return res.json();
}

/**
 * Fetch OHLC quote history for a ticker in a market.
 * @param {{ country: string, provider: string }} market
 * @param {string} ticker
 */
export async function fetchQuotes(market, ticker) {
  const url = apiPath(`${marketRoot(market)}/quotes/${encodeURIComponent(ticker)}`);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Quotes ${ticker}: ${res.status}`);
  const data = await res.json();
  const quotes = data.quotes || [];
  return quotes.slice(-DEFAULT_CHART_DAYS);
}

const DESCRIPTION_CHUNK = 200;

/**
 * Company name and sector for chart headers.
 * Sector is the SIC description when the provider has one, otherwise "".
 * Tickers with no stored description are omitted.
 * @param {{ country: string, provider: string }} market
 * @param {string[]} tickers
 * @returns {Promise<Record<string, { companyName: string, sector: string }>>}
 */
export async function fetchDescriptions(market, tickers) {
  const unique = [...new Set(tickers.filter(Boolean))];
  if (!unique.length) return {};
  const chunks = [];
  for (let i = 0; i < unique.length; i += DESCRIPTION_CHUNK) {
    chunks.push(unique.slice(i, i + DESCRIPTION_CHUNK));
  }
  const parts = await Promise.all(
    chunks.map(async (chunk) => {
      const url = apiPath(`${marketRoot(market)}/descriptions`, { tickers: chunk.join(',') });
      const res = await fetch(url);
      if (!res.ok) throw new Error(`Descriptions: ${res.status}`);
      return res.json();
    })
  );
  return Object.assign({}, ...parts);
}

export { DEFAULT_CHART_DAYS };
