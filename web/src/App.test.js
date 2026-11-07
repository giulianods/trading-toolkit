import { render, screen } from '@testing-library/react';
import App from './App';
import { fetchMarketDataStatus, fetchWatchlist } from './api';

jest.mock('./api', () => ({
  ...jest.requireActual('./api'),
  fetchMarketDataStatus: jest.fn(),
  fetchWatchlist: jest.fn(),
}));

test('renders the market switcher and shows quote and watchlist dates separately', async () => {
  const generatedAt = '2026-11-07T05:30:00Z';
  fetchMarketDataStatus.mockResolvedValue({ latestQuoteDate: '2026-11-06' });
  fetchWatchlist.mockResolvedValue({ day: '2026-11-05', generatedAt, tickers: [] });
  render(<App />);
  expect(screen.getByRole('tab', { name: 'US' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'RO' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'DE' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'JP' })).toBeInTheDocument();
  expect(await screen.findByText('Latest market data: 2026-11-06')).toBeInTheDocument();
  const timestamp = await screen.findByText(new Date(generatedAt).toLocaleString(undefined, { timeZoneName: 'short' }));
  expect(timestamp).toHaveAttribute('datetime', generatedAt);
  expect(timestamp.parentElement).toHaveTextContent('2026-11-05');
});
