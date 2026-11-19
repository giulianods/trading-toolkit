import { fireEvent, render, screen } from '@testing-library/react';
import ChartGrid from './ChartGrid';
import { fetchDescription, fetchDescriptions, fetchIncomeStatements, fetchQuotes } from '../api';
import { SelectionContext } from '../context/SelectionContext';

jest.mock('../api', () => ({
  __esModule: true,
  fetchQuotes: jest.fn(async () => []),
  fetchIncomeStatements: jest.fn(),
  fetchDescription: jest.fn(),
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
  fetchDescription.mockReset();
  fetchDescription.mockResolvedValue({ description: 'Apple designs and sells consumer electronics.' });
  fetchQuotes.mockResolvedValue([]);
  fetchDescriptions.mockResolvedValue(labels);
  fetchIncomeStatements.mockResolvedValue({ annual: [], quarterly: [] });
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

test('ticker text is separate from the watchlist checkbox', async () => {
  const onSelectionChange = jest.fn();
  render(
    <SelectionContext.Provider value={{ selectedTickers: new Set(), onSelectionChange }}>
      <ChartGrid market={market} tickers={['AAPL']} />
    </SelectionContext.Provider>
  );

  const symbol = await screen.findByText('AAPL');
  fireEvent.click(symbol);
  fireEvent.doubleClick(symbol);
  expect(symbol.closest('label')).toBeNull();
  expect(onSelectionChange).not.toHaveBeenCalled();

  expect(fetchDescription).not.toHaveBeenCalled();
  const descriptionButton = screen.getByRole('button', { name: 'Company description for AAPL' });
  fireEvent.mouseEnter(descriptionButton);
  expect(await screen.findByText('Apple designs and sells consumer electronics.')).toBeInTheDocument();
  expect(fetchDescription).toHaveBeenCalledWith(market, 'AAPL', expect.any(AbortSignal));
  expect(screen.getByRole('tooltip').parentElement).toBe(document.body);
  fireEvent.click(descriptionButton);
  expect(onSelectionChange).not.toHaveBeenCalled();
  fireEvent.keyDown(descriptionButton, { key: 'Escape' });
  expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  fireEvent.focus(descriptionButton);
  expect(screen.getByRole('tooltip')).toHaveTextContent('Apple designs and sells consumer electronics.');
  expect(fetchDescription).toHaveBeenCalledTimes(1);

  fireEvent.click(screen.getByRole('checkbox', { name: /Include AAPL/ }));
  expect(onSelectionChange).toHaveBeenCalledWith('AAPL', true);
});

test('description icon remains available when company information is missing', async () => {
  fetchDescriptions.mockResolvedValue({});
  fetchDescription.mockResolvedValue({ description: '   ' });
  render(<ChartGrid market={market} tickers={['XYZ']} />);

  const button = await screen.findByRole('button', { name: 'Company description for XYZ' });
  fireEvent.focus(button);
  expect(await screen.findByText('Company description is not available.')).toBeInTheDocument();
});

test('keeps both financial periods visible on narrow screens', async () => {
  const originalWidth = window.innerWidth;
  window.innerWidth = 480;
  try {
    render(<ChartGrid market={market} tickers={['AAPL']} />);
    expect(await screen.findByRole('figure', { name: 'Annual financials' })).toBeInTheDocument();
    expect(screen.getByRole('figure', { name: 'Quarterly financials' })).toBeInTheDocument();
  } finally {
    window.innerWidth = originalWidth;
  }
});
