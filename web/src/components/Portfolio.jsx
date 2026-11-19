import React, { useEffect, useState } from 'react';
import { fetchPortfolio } from '../api';

const number = (v) => Number(v).toLocaleString(undefined, { maximumFractionDigits: 8 });
const pnlClass = (v) => Number(v) >= 0 ? 'pnl-positive' : 'pnl-negative';

function EquityChart({ history, money }) {
  const [range, setRange] = useState('ALL');
  const count = { '1M': 30, '3M': 90, '1Y': 365 }[range];
  const end = history.length ? Date.parse(history[history.length - 1].date) : 0;
  const points = count ? history.filter((p) => Date.parse(p.date) >= end - count * 86400000) : history;
  const values = points.map((p) => Number(p.equity));
  const min = Math.min(...values), max = Math.max(...values);
  const span = max - min || Math.max(Math.abs(max) * 0.01, 1);
  const start = points.length ? Date.parse(points[0].date) : 0;
  const coords = points.map((p) => [20 + (Date.parse(p.date) - start) / (end - start || 1) * 960, 170 - (Number(p.equity) - min) / span * 140]);
  return <div className="portfolio-chart panel"><div className="panel-heading"><h2>Equity history</h2><div className="range-buttons">{['1M', '3M', '1Y', 'ALL'].map((r) => <button key={r} aria-pressed={r === range} onClick={() => setRange(r)}>{r}</button>)}</div></div>
    {!points.length ? <p className="muted">Your equity curve starts with your first recorded trade.</p> : <>
      <div className="chart-extents"><span>{money(max)}</span><span>{money(min)}</span></div>
      <svg viewBox="0 0 1000 200" role="img" aria-label={`Portfolio equity from ${points[0].date} to ${points[points.length - 1].date}`}>
        <defs><linearGradient id="equity-fill" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#34d399" stopOpacity="0.25" /><stop offset="100%" stopColor="#34d399" stopOpacity="0" /></linearGradient></defs>
        <path d={`M${coords[0][0]},190 L${coords.map((p) => p.join(',')).join(' L')} L${coords[coords.length - 1][0]},190 Z`} fill="url(#equity-fill)" />
        <polyline points={coords.map((p) => p.join(',')).join(' ')} fill="none" stroke="#34d399" strokeWidth="2.5" />
        {coords.length === 1 && <circle cx={coords[0][0]} cy={coords[0][1]} r="4" fill="#34d399" />}
      </svg><div className="chart-dates"><span>{points[0].date}</span><span>{points[points.length - 1].date}</span></div>
    </>}
  </div>;
}

export default function Portfolio({ market }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    setData(null); setError(null);
    fetchPortfolio(market, controller.signal).then(setData).catch((e) => { if (!controller.signal.aborted) setError(e.message); });
    return () => controller.abort();
  }, [market, refresh]);
  const money = (v) => Number(v).toLocaleString(undefined, { style: 'currency', currency: data?.currency || 'USD' });
  return <section className="workspace-section">
    <div className="section-title"><div><span className="eyebrow">Paper portfolio</span><h1>Portfolio</h1><p>{market.label} · Cash account</p></div><button className="secondary-button" onClick={() => setRefresh((v) => v + 1)}>Refresh portfolio</button></div>
    {error ? <div className="section-empty" role="alert">{error}</div> : !data ? <div className="section-empty" role="status">Loading portfolio…</div> : <>
      <div className="portfolio-summary">
        <div className="equity-summary"><span>Portfolio value</span><strong>{money(data.equity)}</strong><small className={pnlClass(data.totalPnl)}>{money(data.totalPnl)} ({Number(data.returnPercent).toFixed(2)}%) all time</small></div>
        {[['Cash / buying power', data.cash], ['Invested', data.marketValue], ['Realized P/L', data.realizedPnl], ['Unrealized P/L', data.unrealizedPnl]].map(([label, value]) => <div key={label}><span>{label}</span><strong className={label.includes('P/L') ? pnlClass(value) : ''}>{money(value)}</strong></div>)}
      </div>
      <EquityChart history={data.history} money={money} />
      <div className="panel"><div className="panel-heading"><h2>Positions</h2><span>{data.positions.length} holdings</span></div>
        {!data.positions.length ? <p className="section-empty">No open positions. Starting capital: {money(data.initialCapital)}.</p> : <div className="table-scroll"><table><thead><tr>{['Symbol', 'Quantity', 'Avg. entry', 'Last price', 'Market value', 'Unrealized P/L', 'Price as of'].map((h) => <th key={h}>{h}</th>)}</tr></thead><tbody>
          {data.positions.map((p) => <tr key={p.ticker}><th>{p.ticker}</th><td>{number(p.quantity)}</td><td>{money(p.averageEntryPrice)}</td><td>{money(p.currentPrice)}</td><td>{money(p.marketValue)}</td><td className={pnlClass(p.unrealizedPnl)}>{money(p.unrealizedPnl)}</td><td>{p.priceSource === 'last_fill' ? 'Last fill (no close available)' : p.quoteDate}</td></tr>)}
        </tbody></table></div>}
      </div>
      <div className="panel"><div className="panel-heading"><h2>Activity</h2><span>{data.trades.length} recorded fills</span></div>
        {!data.trades.length ? <p className="section-empty">Buy and sell records submitted by your agent will appear here.</p> : <div className="table-scroll"><table><thead><tr>{['Executed', 'Side', 'Symbol', 'Quantity', 'Fill price', 'Fees', 'Notes'].map((h) => <th key={h}>{h}</th>)}</tr></thead><tbody>
          {data.trades.map((t) => <tr key={t.id}><td><time dateTime={t.executedAt}>{new Date(t.executedAt).toLocaleString()}</time></td><td><span className={`trade-side trade-side-${t.side}`}>{t.side}</span></td><th>{t.ticker}</th><td>{number(t.quantity)}</td><td>{money(t.price)}</td><td>{money(t.fees)}</td><td className="trade-notes">{t.notes || '—'}</td></tr>)}
        </tbody></table></div>}
      </div>
      <p className="muted portfolio-note">Valued using stored daily closes; prices may be delayed. Starting capital {money(data.initialCapital)}. Average cost accounting; fees included.</p>
    </>}
  </section>;
}
