import { fireEvent, render, screen } from '@testing-library/react';
import FinancialCharts from './FinancialCharts';
import { fetchIncomeStatements } from '../api';

jest.mock('../api', () => ({ fetchIncomeStatements: jest.fn() }));

test('shows annual totals and switches to quarterly results with a loss', async () => {
  fetchIncomeStatements.mockResolvedValue({
    annual: [{ date: '2025-12-31', currency: 'RON', revenue: 2000000, netIncome: 200000, netMarginPct: 10, eps: 1.25 }],
    quarterly: [{ date: '2026-03-31', currency: 'RON', revenue: 400000, netIncome: -20000, netMarginPct: -5 }],
  });
  render(<FinancialCharts market={{ country: 'ro', provider: 'bvb' }} ticker="TLV" />);

  expect(await screen.findByRole('img', { name: 'Annual Revenue' })).toBeInTheDocument();
  expect(screen.getByText('1.25 RON')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Quarterly' }));
  expect(screen.getByRole('img', { name: 'Quarterly Net income' }).querySelector('rect'))
    .toHaveClass('financial-bar--negative');
  expect(screen.getByRole('img', { name: 'Quarterly Net margin %' })).toBeInTheDocument();
});
