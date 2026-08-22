import { render, screen } from '@testing-library/react';
import App from './App';

test('renders market switcher', () => {
  render(<App />);
  expect(screen.getByRole('tab', { name: 'US' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'RO' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'DE' })).toBeInTheDocument();
  expect(screen.getByRole('tab', { name: 'JP' })).toBeInTheDocument();
});
