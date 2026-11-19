import { fireEvent, render, screen, within } from '@testing-library/react';
import App from './App';
import { fetchMarkets, fetchMarketDataStatus, fetchScreens, fetchWatchlist, fetchReports, fetchPortfolio } from './api';

jest.mock('./api', () => ({
  ...jest.requireActual('./api'),
  fetchMarketDataStatus: jest.fn(),
  fetchMarkets: jest.fn(),
  fetchScreens: jest.fn(),
  fetchWatchlist: jest.fn(),
  fetchReports: jest.fn(),
  fetchPortfolio: jest.fn(),
}));

beforeEach(() => {
  fetchWatchlist.mockClear();
  window.localStorage.clear();
  fetchMarkets.mockResolvedValue([
    { id: 'us-massive', country: 'us', provider: 'massive', label: 'US · Massive + Finnhub', title: 'Massive prices / Finnhub financials', aliases: [] },
    { id: 'us-alpaca', country: 'us', provider: 'alpaca', label: 'US · Alpaca + Finnhub', title: 'Alpaca prices / Finnhub financials', aliases: ['us-finnhub'] },
    { id: 'ro-bvb', country: 'ro', provider: 'bvb', label: 'Romania · BVB', title: 'BVB prices', aliases: [] },
  ]);
  fetchMarketDataStatus.mockResolvedValue({ latestQuoteDate: '2026-11-06' });
  fetchReports.mockResolvedValue([]);
  fetchPortfolio.mockResolvedValue({ currency: 'USD', initialCapital: 100000, cash: 100000, equity: 100000, marketValue: 0,
    totalPnl: 0, returnPercent: 0, realizedPnl: 0, unrealizedPnl: 0, positions: [], trades: [], history: [] });
});

test('logo opens the section menu, switches sections and preserves market selection', async () => {
  fetchScreens.mockResolvedValue([{ id: 'SEPA', label: 'SEPA' }]);
  fetchWatchlist.mockResolvedValue({ tickers: [] });
  render(<App />);
  const logo = await screen.findByRole('button', { name: 'Open navigation menu' });
  fireEvent.click(logo);
  const menu = screen.getByRole('dialog', { name: 'Navigation' });
  expect(within(menu).getAllByRole('button').slice(1).map((b) => b.textContent.slice(2))).toEqual(['Screens', 'Reports', 'Portfolio']);
  fireEvent.click(within(menu).getByRole('button', { name: /Reports/ }));
  expect(await screen.findByText('No reports yet')).toBeInTheDocument();
  expect(screen.queryByRole('combobox', { name: 'Screen' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('tab', { name: 'Romania · BVB' }));
  expect(await screen.findByText('Romania · BVB · Agent research, latest first')).toBeInTheDocument();
  expect(fetchReports).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'ro-bvb' }), expect.any(AbortSignal));
  fireEvent.click(logo);
  fireEvent.click(screen.getByRole('button', { name: /Portfolio/ }));
  expect(await screen.findByText('No open positions. Starting capital:', { exact: false })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Romania · BVB' })).toHaveAttribute('aria-selected', 'true');
  fireEvent.click(logo);
  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

test('renders the market switcher and shows quote and watchlist dates separately', async () => {
  const generatedAt = '2026-11-07T05:30:00Z';
  fetchScreens.mockResolvedValue([{ id: 'SEPA', label: 'SEPA' }]);
  fetchWatchlist.mockResolvedValue({ day: '2026-11-05', generatedAt, tickers: [] });
  render(<App />);
  expect(await screen.findByRole('tab', { name: 'US · Massive + Finnhub' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'US · Alpaca + Finnhub' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'Romania · BVB' })).toBeInTheDocument();
  expect(screen.queryByRole('tab', { name: 'JP' })).not.toBeInTheDocument();
  expect(await screen.findByText('Latest market data: 2026-11-06')).toBeInTheDocument();
  const timestamp = await screen.findByText(new Date(generatedAt).toLocaleString(undefined, { timeZoneName: 'short' }));
  expect(timestamp).toHaveAttribute('datetime', generatedAt);
  expect(screen.getByText(/\(2026-11-05\)/)).toBeInTheDocument();
});

test('uses the configured screen order and requests the selected watchlist', async () => {
  fetchScreens.mockResolvedValue([
    { id: '7_PCT_UP', label: '7%UP' },
    { id: 'SEPA', label: 'SEPA' },
  ]);
  fetchWatchlist.mockImplementation(async (_, name) => ({
    screenName: name,
    day: name === '7_PCT_UP' ? '2026-11-06' : '2026-11-05',
    tickers: [],
  }));
  render(<App />);

  const selector = await screen.findByRole('combobox', { name: 'Screen' });
  expect(await screen.findByText(/\(2026-11-06\)/)).toBeInTheDocument();
  expect(within(selector).getAllByRole('option').map((option) => option.textContent)).toEqual(['7%UP', 'SEPA']);
  expect(selector).toHaveValue('7_PCT_UP');
  expect(fetchWatchlist).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'us-massive' }), '7_PCT_UP');

  fireEvent.change(selector, { target: { value: 'SEPA' } });
  expect(await screen.findByText(/\(2026-11-05\)/)).toBeInTheDocument();
  expect(fetchWatchlist).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'us-massive' }), 'SEPA');
});

test('restores a legacy profile alias and only shows server-configured profiles', async () => {
  window.localStorage.setItem('ttk.selectedMarket', 'us-finnhub');
  fetchScreens.mockResolvedValue([{ id: 'SEPA', label: 'SEPA' }]);
  fetchWatchlist.mockResolvedValue({ day: '2026-11-05', tickers: [] });
  render(<App />);
  const tab = await screen.findByRole('tab', { name: 'US · Alpaca + Finnhub' });
  expect(tab).toHaveAttribute('aria-selected', 'true');
  expect(screen.queryByRole('tab', { name: 'US Finnhub' })).not.toBeInTheDocument();
  expect(fetchWatchlist).toHaveBeenCalledWith(expect.objectContaining({ id: 'us-alpaca' }), 'SEPA');
});
