import { fireEvent, render, screen, within } from '@testing-library/react';
import App from './App';
import { fetchMarketDataStatus, fetchScreens, fetchWatchlist } from './api';

jest.mock('./api', () => ({
  ...jest.requireActual('./api'),
  fetchMarketDataStatus: jest.fn(),
  fetchScreens: jest.fn(),
  fetchWatchlist: jest.fn(),
}));

beforeEach(() => {
  fetchWatchlist.mockClear();
  fetchMarketDataStatus.mockResolvedValue({ latestQuoteDate: '2026-11-06' });
});

test('renders the market switcher and shows quote and watchlist dates separately', async () => {
  const generatedAt = '2026-11-07T05:30:00Z';
  fetchScreens.mockResolvedValue([{ id: 'SEPA', label: 'SEPA' }]);
  fetchWatchlist.mockResolvedValue({ day: '2026-11-05', generatedAt, tickers: [] });
  render(<App />);
  expect(screen.getByRole('tab', { name: 'US Massive' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'US Alpaca' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'RO' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'DE' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'JP' })).toBeInTheDocument();
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

  const selector = screen.getByRole('combobox', { name: 'Screen' });
  expect(await screen.findByText(/\(2026-11-06\)/)).toBeInTheDocument();
  expect(within(selector).getAllByRole('option').map((option) => option.textContent)).toEqual(['7%UP', 'SEPA']);
  expect(selector).toHaveValue('7_PCT_UP');
  expect(fetchWatchlist).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'us-massive' }), '7_PCT_UP');

  fireEvent.change(selector, { target: { value: 'SEPA' } });
  expect(await screen.findByText(/\(2026-11-05\)/)).toBeInTheDocument();
  expect(fetchWatchlist).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'us-massive' }), 'SEPA');
});
