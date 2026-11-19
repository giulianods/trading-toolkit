import { useEffect, useState } from 'react';
import { fetchIncomeStatements } from '../api';

const METRICS = [
  { key: 'revenue', label: 'Revenue' },
  { key: 'netIncome', label: 'Net income' },
  { key: 'netMarginPct', label: 'Margin', percent: true },
];
const compactNumber = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const exactNumber = new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 });
const WIDTH = 240;
const HEIGHT = 80;

function FinancialPeriod({ periods, annual }) {
  const label = annual ? 'Annual' : 'Quarterly';
  const latest = periods.at(-1);
  const currency = latest?.currency;
  // Keep every date aligned, but don't compare monetary totals in different currencies.
  const money = periods.flatMap((period) => period.currency === currency
    ? [period.revenue, period.netIncome].filter(Number.isFinite) : []);
  const margins = periods.map((period) => period.netMarginPct).filter(Number.isFinite);
  const moneyMin = Math.min(0, ...money);
  const moneyMax = Math.max(0, ...money);
  const marginMin = Math.min(0, ...margins);
  const marginMax = Math.max(0, ...margins);
  const scale = (value, min, max) => HEIGHT - 2 - (value - min) / (max - min || 1) * (HEIGHT - 4);
  const moneyY = (value) => scale(value, moneyMin, moneyMax);
  const marginY = (value) => scale(value, marginMin, marginMax);
  const baseline = moneyY(0);
  const step = WIDTH / (periods.length || 1);
  const barWidth = step * 0.28;
  const format = (value, percent = false, exact = false) => Number.isFinite(value)
    ? `${(exact ? exactNumber : compactNumber).format(value)}${percent ? '%' : currency ? ` ${currency}` : ''}` : '—';
  // A missing margin breaks the line rather than connecting across unavailable data.
  const marginSegments = [];
  periods.forEach((period, index) => {
    if (!Number.isFinite(period.netMarginPct)) return;
    if (index === 0 || !Number.isFinite(periods[index - 1].netMarginPct)) marginSegments.push([]);
    marginSegments.at(-1).push(`${step * (index + 0.5)},${marginY(period.netMarginPct)}`);
  });

  return (
    <figure className="financial-period" aria-label={`${label} financials`}>
      <figcaption><strong>{label}</strong><span>{currency || 'Amount'} / %</span></figcaption>
      {money.length || margins.length ? (
        <>
          <div className="financial-plot">
            <span className="financial-axis financial-axis--left financial-axis--top">{money.length ? compactNumber.format(moneyMax) : '—'}</span>
            <span className="financial-axis financial-axis--left financial-axis--bottom">{money.length ? compactNumber.format(moneyMin) : '—'}</span>
            <span className="financial-axis financial-axis--right financial-axis--top">{margins.length ? `${compactNumber.format(marginMax)}%` : '—'}</span>
            <span className="financial-axis financial-axis--right financial-axis--bottom">{margins.length ? `${compactNumber.format(marginMin)}%` : '—'}</span>
            <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} preserveAspectRatio="none" role="img" aria-label={`${label} revenue, net income and margin`}>
              <line x1="0" x2={WIDTH} y1={baseline} y2={baseline} className="financial-baseline" />
              {periods.map((period, index) => {
                const center = step * (index + 0.5);
                const sameCurrency = period.currency === currency;
                return (
                  <g key={period.date}>
                    <title>{`${period.date} · Revenue: ${sameCurrency ? format(period.revenue, false, true) : 'Different currency'} · Net income: ${sameCurrency ? format(period.netIncome, false, true) : 'Different currency'} · Margin: ${format(period.netMarginPct, true, true)}`}</title>
                    {METRICS.slice(0, 2).map((metric, offset) => sameCurrency && Number.isFinite(period[metric.key]) && (
                      <rect key={metric.key} x={center + (offset - 1) * barWidth} y={Math.min(baseline, moneyY(period[metric.key]))}
                        width={barWidth - 1} height={Math.abs(moneyY(period[metric.key]) - baseline)}
                        className={`financial-bar financial-series--${metric.key}`} />
                    ))}
                    {Number.isFinite(period.netMarginPct) && <circle cx={center} cy={marginY(period.netMarginPct)} r="1.8" className="financial-margin-point" />}
                  </g>
                );
              })}
              {marginSegments.map((points, index) => <polyline key={index} points={points.join(' ')} className="financial-margin-line" />)}
            </svg>
            <div className="financial-dates" aria-hidden="true">
              {periods.map((period) => <span key={period.date}>{annual ? period.date.slice(0, 4) : period.date.slice(2, 7)}</span>)}
            </div>
          </div>
          {annual && <dl className="financial-legend">
            {METRICS.map((metric) => <div key={metric.key} className={`financial-series--${metric.key}`}>
              <dt>{metric.label}</dt><dd>{format(latest?.[metric.key], metric.percent)}</dd>
            </div>)}
          </dl>}
        </>
      ) : <p className="financial-message">No statements for this period.</p>}
      {annual && periods.length > 0 && <p className="financial-eps">EPS <strong>{format(latest?.eps, false, true)}</strong></p>}
    </figure>
  );
}

export default function FinancialCharts({ market, ticker }) {
  const [statements, setStatements] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setStatements(null);
    setFailed(false);
    fetchIncomeStatements(market, ticker, controller.signal)
      .then((data) => { if (!controller.signal.aborted) setStatements(data); })
      .catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => controller.abort();
  }, [market, ticker]);

  return (
    <section className="financial-panel" aria-label={`${ticker} financials`} onClick={(event) => event.stopPropagation()} onDoubleClick={(event) => event.stopPropagation()}>
      {failed || !statements ? <p className="financial-message">{failed ? 'Financial data could not be loaded.' : 'Loading financials…'}</p> : <>
        <FinancialPeriod periods={(statements.annual ?? []).slice(-5)} annual />
        <FinancialPeriod periods={(statements.quarterly ?? []).slice(-6)} annual={false} />
      </>}
    </section>
  );
}
