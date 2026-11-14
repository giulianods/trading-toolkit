import { render } from '@testing-library/react';
import { createChart } from 'lightweight-charts';
import OhlcChart from './OhlcChart';

jest.mock('lightweight-charts', () => ({
  createChart: jest.fn(),
  LineStyle: { Dashed: 2 },
  LineType: { Curved: 2 },
}));

function draw(highsAndLows) {
  const quotes = Array.from({ length: 3 * (highsAndLows.length - 1) + 1 }, (_, i) => ({
    day: new Date(Date.UTC(2026, 8, i + 1)).toISOString().slice(0, 10),
    l: i % 3 === 0 ? highsAndLows[i / 3] : (i % 2 === 0 ? 89 : 95),
    o: 100, h: 102, c: 101,
  }));
  const swingPoints = highsAndLows.map((price, i) => [quotes[3 * i].day, price]);
  const arcs = [];
  createChart.mockReturnValue({
    addCandlestickSeries: () => ({ setData: jest.fn(), createPriceLine: jest.fn() }),
    addLineSeries: () => ({ setData: points => arcs.push(points) }),
    timeScale: () => ({ fitContent: jest.fn() }),
    remove: jest.fn(),
  });
  render(<OhlcChart ticker="TEST" quotes={quotes} vcpStats={{ swingPoints: JSON.stringify(swingPoints) }} />);
  return { arcs, quotes };
}

test('merges small dips into broad arcs with clearance below the wicks', () => {
  const { arcs, quotes } = draw([100, 99, 100, 88, 100, 99, 100, 90, 100, 99, 100]);

  expect(arcs).toHaveLength(2);
  expect(arcs[0][0].time).toBe(quotes[0].day);
  expect(arcs.at(-1).at(-1).time).toBe(quotes.at(-1).day);
  for (const arc of arcs) {
    const values = arc.map(({ value }) => value);
    const trough = values.indexOf(Math.min(...values));
    expect(trough).toBeGreaterThan(0);
    expect(trough).toBeLessThan(arc.length - 1);
    arc.forEach(({ time, value }, i) => {
      expect(value).toBeLessThan(quotes.find(q => q.day === time).l);
      if (i > 0) expect(i <= trough ? value <= values[i - 1] : value >= values[i - 1]).toBe(true);
    });
  }
});

test('keeps three clear cups and caps a crowded base at four arcs', () => {
  expect(draw([100, 88, 100, 89, 100, 90, 100, 99, 100]).arcs).toHaveLength(3);
  expect(draw([100, 88, 100, 89, 100, 90, 100, 90, 100, 91, 100]).arcs).toHaveLength(4);
});
