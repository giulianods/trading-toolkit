import { fireEvent, render, screen } from '@testing-library/react';
import Reports from './Reports';
import { fetchReports } from '../api';
jest.mock('../api', () => ({ fetchReports: jest.fn() }));

test('renders Markdown safely and lets the reader select an older report', async () => {
  fetchReports.mockResolvedValue([
    { id: 'new', title: 'New report', asOf: '2026-11-18', createdAt: '2026-11-18T12:00:00Z', markdown: '# Outlook\n\n**Strong** breadth\n\n<script>alert(1)</script>\n\n[bad](javascript:alert(1))\n\n| A | B |\n| - | - |\n| 1 | 2 |' },
    { id: 'old', title: 'Old report', asOf: '2026-11-15', createdAt: '2026-11-15T12:00:00Z', markdown: '## Earlier outlook\n\nCash remains available.' },
  ]);
  const { container } = render(<Reports market={{ id: 'ro-bvb', label: 'Romania' }} />);
  expect(await screen.findByRole('heading', { name: 'Outlook' })).toBeInTheDocument();
  expect(container.querySelector('.markdown-content strong')).toHaveTextContent('Strong');
  expect(container.querySelector('.markdown-content table')).toBeInTheDocument();
  expect(container.querySelector('script')).toBeNull();
  expect(container.querySelector('a[href^="javascript:"]')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: /Old report/ }));
  expect(screen.getByRole('heading', { name: 'Earlier outlook' })).toBeInTheDocument();
});

test('shows a fetch error with a retry action', async () => {
  fetchReports.mockRejectedValueOnce(new Error('Reports unavailable')).mockResolvedValueOnce([]);
  render(<Reports market={{ label: 'US' }} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Reports unavailable');
  fireEvent.click(screen.getByRole('button', { name: 'Refresh reports' }));
  expect(await screen.findByText('No reports yet')).toBeInTheDocument();
});
