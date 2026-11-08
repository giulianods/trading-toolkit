import { useEffect, useState } from 'react';
import { fetchIncomeStatements } from '../api';

const METRICS = [
  { key: 'revenue', label: 'Revenue' },
  { key: 'netIncome', label: 'Net income' },
  { key: 'netMarginPct', label: 'Net margin %', percent: true },
];

const compactNumber = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
const exactNumber = new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 });
const PLOT_WIDTH = 180;

function MetricBars({ metric, periods, annual }) {
  const currency = periods.at(-1)?.currency;
  const comparable = metric.percent ? periods : periods.filter((period) => period.currency === currency);
  const values = comparable.map((period) => period[metric.key]).filter(Number.isFinite);
  const format = (value, formatter) => {
    const unit = metric.percent ? '%' : currency ? ` ${currency}` : '';
    return `${formatter.format(value)}${unit}`;
  };
  const min = Math.min(0, ...values);
  const max = Math.max(0, ...values);
  const scale = (value) => 35 - ((value - min) / (max - min || 1)) * 32;
  const baseline = scale(0);
  const step = PLOT_WIDTH / (comparable.length || 1);
  const barWidth = step * 0.28;
  const latestValue = comparable.at(-1)?.[metric.key];

  return (
    <figure className="financial-metric">
      <figcaption>
        <span>{metric.label}</span>
        <span>{Number.isFinite(latestValue) ? format(latestValue, compactNumber) : '—'}</span>
      </figcaption>
      {values.length ? (
        <svg viewBox={`0 0 ${PLOT_WIDTH} 49`} role="img" aria-label={`${annual ? 'Annual' : 'Quarterly'} ${metric.label}`}>
          <line x1="0" x2={PLOT_WIDTH} y1={baseline} y2={baseline} className="financial-baseline" />
          {comparable.map((period, index) => {
            const value = period[metric.key];
            const center = step * (index + 0.5);
            return (
              <g key={period.date}>
                <title>{`${period.date} · ${Number.isFinite(value) ? format(value, exactNumber) : 'Unavailable'}`}</title>
                {Number.isFinite(value) && (
                  <rect
                    x={center - barWidth / 2}
                    y={Math.min(baseline, scale(value))}
                    width={barWidth}
                    height={Math.max(1, Math.abs(scale(value) - baseline))}
                    className={value < 0 ? 'financial-bar financial-bar--negative' : 'financial-bar'}
                    rx="1"
                  />
                )}
                <text x={center} y="47" textAnchor="middle">
                  {annual ? period.date.slice(0, 4) : period.date.slice(2, 7)}
                </text>
              </g>
            );
          })}
        </svg>
      ) : <p className="financial-message">Unavailable for these periods</p>}
    </figure>
  );
}

export default function FinancialCharts({ market, ticker }) {
  const [statements, setStatements] = useState(null);
  const [failed, setFailed] = useState(false);
  const [frequency, setFrequency] = useState('annual');

  useEffect(() => {
    const controller = new AbortController();
    setStatements(null);
    setFailed(false);
    fetchIncomeStatements(market, ticker, controller.signal)
      .then((data) => { if (!controller.signal.aborted) setStatements(data); })
      .catch(() => { if (!controller.signal.aborted) setFailed(true); });
    return () => controller.abort();
  }, [market, ticker]);

  const annual = frequency === 'annual';
  const periods = (statements?.[frequency] ?? []).slice(annual ? -5 : -6);
  const latestEps = periods.at(-1);

  return (
    <section className="financial-panel" aria-label={`${ticker} financials`} onClick={(event) => event.stopPropagation()} onDoubleClick={(event) => event.stopPropagation()}>
      <div className="financial-toolbar" aria-label="Financial period">
        {['annual', 'quarterly'].map((value) => (
          <button key={value} type="button" aria-pressed={frequency === value} onClick={() => setFrequency(value)}>
            {value === 'annual' ? 'Annual' : 'Quarterly'}
          </button>
        ))}
      </div>
      {periods.length ? (
        <>
          {METRICS.map((metric) => <MetricBars key={metric.key} metric={metric} periods={periods} annual={annual} />)}
          <p className="financial-eps">
            EPS <strong>{Number.isFinite(latestEps.eps) ? `${exactNumber.format(latestEps.eps)}${latestEps.currency ? ` ${latestEps.currency}` : ''}` : '—'}</strong>
          </p>
        </>
      ) : (
        <p className="financial-message">
          {failed ? 'Financial data could not be loaded.' : statements ? 'No statements for this period.' : 'Loading financials…'}
        </p>
      )}
    </section>
  );
}
