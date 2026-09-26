import { render, screen } from '@testing-library/react';
import ChartGrid from './ChartGrid';
import { fetchDescriptions, fetchQuotes } from '../api';

jest.mock('../api', () => ({
  __esModule: true,
  fetchQuotes: jest.fn(async () => []),
  fetchDescriptions: jest.fn(async () => ({
    AAPL: { companyName: 'Apple Inc.', sector: 'Electronic Computers' },
    XYZ: { companyName: 'No Sector Co', sector: '   ' },
  })),
}));

const market = { id: 'us-massive', country: 'us', provider: 'massive' };

const labels = {
  AAPL: { companyName: 'Apple Inc.', sector: 'Electronic Computers' },
  XYZ: { companyName: 'No Sector Co', sector: '   ' },
};

beforeEach(() => {
  fetchQuotes.mockResolvedValue([]);
  fetchDescriptions.mockResolvedValue(labels);
});

test('shows company name and sector in the chart header', async () => {
  render(<ChartGrid market={market} tickers={['AAPL', 'XYZ']} />);

  expect(await screen.findByText('Apple Inc.')).toBeInTheDocument();
  expect(screen.getByText('Electronic Computers')).toBeInTheDocument();
  expect(screen.getByText('No Sector Co')).toBeInTheDocument();

  const apple = screen.getByText('AAPL').closest('.chart-ticker');
  expect(apple.querySelector('.chart-ticker-sector')).toHaveTextContent('Electronic Computers');

  const xyz = screen.getByText('XYZ').closest('.chart-ticker');
  expect(xyz.querySelector('.chart-ticker-name')).toHaveTextContent('No Sector Co');
  expect(xyz.querySelector('.chart-ticker-sector')).toBeNull();

  expect(fetchDescriptions).toHaveBeenCalledWith(market, ['AAPL', 'XYZ']);
  expect(fetchQuotes).toHaveBeenCalled();
});
