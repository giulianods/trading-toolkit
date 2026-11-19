import { render, screen, within } from '@testing-library/react';
import FinancialCharts from './FinancialCharts';
import { fetchIncomeStatements } from '../api';

jest.mock('../api', () => ({ fetchIncomeStatements: jest.fn() }));
const market = { country: 'ro', provider: 'bvb' };

test('shows combined annual and quarterly graphs together, with signed income and a percentage axis', async () => {
  fetchIncomeStatements.mockResolvedValue({
    annual: [{ date: '2025-12-31', currency: 'RON', revenue: 2000000, netIncome: 200000, netMarginPct: 10, eps: 1.25 }],
    quarterly: [{ date: '2026-03-31', currency: 'RON', revenue: 400000, netIncome: -20000, netMarginPct: -5 }],
  });
  render(<FinancialCharts market={market} ticker="TLV" />);

  const annual = await screen.findByRole('img', { name: 'Annual revenue, net income and margin' });
  const quarterly = screen.getByRole('img', { name: 'Quarterly revenue, net income and margin' });
  expect(annual.compareDocumentPosition(quarterly) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.queryByRole('button', { name: /Annual|Quarterly/ })).not.toBeInTheDocument();
  expect(screen.getAllByRole('img')).toHaveLength(2);
  expect(quarterly.querySelectorAll('rect')).toHaveLength(2);
  expect(quarterly.querySelector('title')).toHaveTextContent('Net income: -20,000 RON · Margin: -5%');
  const baseline = Number(quarterly.querySelector('line').getAttribute('y1'));
  expect(Number(quarterly.querySelector('.financial-series--netIncome').getAttribute('y'))).toBe(baseline);
  expect(screen.getByText('1.25 RON')).toBeInTheDocument();
  const quarterlyPanel = within(screen.getByRole('figure', { name: 'Quarterly financials' }));
  expect(quarterlyPanel.queryByText('Revenue')).not.toBeInTheDocument();
  expect(quarterlyPanel.queryByText('EPS')).not.toBeInTheDocument();
});

test('keeps dates aligned across missing values and currency changes, and breaks the margin line at gaps', async () => {
  fetchIncomeStatements.mockResolvedValue({ annual: [
    { date: '2023-12-31', currency: 'EUR', revenue: 900, netIncome: 90, netMarginPct: 10 },
    { date: '2024-12-31', currency: 'RON', revenue: 0, netIncome: null, netMarginPct: null },
    { date: '2025-12-31', currency: 'RON', revenue: 200, netIncome: -10, netMarginPct: -5 },
  ], quarterly: [] });
  render(<FinancialCharts market={market} ticker="TLV" />);
  const chart = await screen.findByRole('img', { name: 'Annual revenue, net income and margin' });
  expect(chart.querySelectorAll('rect')).toHaveLength(3);
  expect(chart.querySelectorAll('polyline')).toHaveLength(2);
  expect(chart.outerHTML).not.toMatch(/NaN|Infinity/);
  const quarterly = screen.getByRole('figure', { name: 'Quarterly financials' });
  expect(within(quarterly).getByText('No statements for this period.')).toBeInTheDocument();
});
