import React, { useEffect, useMemo, useRef, useState } from 'react';
import MarkdownIt from 'markdown-it';
import { fetchReports } from '../api';

// Disable raw HTML and inline images. markdown-it also blocks unsafe link protocols.
const markdown = new MarkdownIt({ html: false, linkify: true, breaks: false });
markdown.disable('image');

export default function Reports({ market }) {
  const [reports, setReports] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [error, setError] = useState(null);
  const [refresh, setRefresh] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [olderError, setOlderError] = useState(null);
  const activeRequest = useRef(null);
  useEffect(() => {
    const controller = new AbortController();
    activeRequest.current = controller;
    setReports(null); setError(null); setSelectedId(null); setOlderError(null); setLoadingMore(false);
    fetchReports(market, controller.signal).then((rows) => {
      if (!controller.signal.aborted) { setReports(rows); setHasMore(rows.length === 50); }
    }).catch((e) => {
      if (!controller.signal.aborted) setError(e.message);
    });
    return () => controller.abort();
  }, [market, refresh]);
  const selected = reports?.find((r) => r.id === selectedId) ?? reports?.[0];
  const html = useMemo(() => markdown.render(selected?.markdown ?? ''), [selected?.markdown]);
  return (
    <section className="workspace-section">
      <div className="section-title"><div><span className="eyebrow">Market intelligence</span><h1>Reports</h1><p>{market.label} · Agent research, latest first</p></div><button className="secondary-button" onClick={() => setRefresh((v) => v + 1)}>Refresh reports</button></div>
      {error ? <div role="alert" className="section-empty">{error}</div> : !reports ? <div role="status" className="section-empty">Loading reports…</div> : !reports.length ? (
        <div className="section-empty"><h2>No reports yet</h2><p>Market reports published by your agent will appear here.</p></div>
      ) : <div className="reports-layout">
        <nav className="report-list" aria-label="Market reports">
          {reports.map((report) => <button key={report.id} aria-current={report.id === selected?.id ? 'true' : undefined} onClick={() => setSelectedId(report.id)}>
            <span>As of {report.asOf}</span><strong>{report.title}</strong><small>Published {new Date(report.createdAt).toLocaleString()}</small>
          </button>)}
          {hasMore && <button disabled={loadingMore} onClick={async () => {
            const controller = activeRequest.current;
            setLoadingMore(true); setOlderError(null);
            try {
              const rows = await fetchReports(market, controller.signal, reports.length);
              if (controller.signal.aborted) return;
              setReports((previous) => [...previous, ...rows.filter((r) => !previous.some((p) => p.id === r.id))]);
              setHasMore(rows.length === 50);
            } catch (e) { if (!controller.signal.aborted) setOlderError(e.message); }
            finally { if (!controller.signal.aborted) setLoadingMore(false); }
          }}>{loadingMore ? 'Loading…' : 'Load older reports'}</button>}
          {olderError && <p role="alert">{olderError}</p>}
        </nav>
        <article className="report-document">
          <div className="report-document-header"><span className="eyebrow">{selected.asOf} · {market.label}</span><h2>{selected.title}</h2><p>Published <time dateTime={selected.createdAt}>{new Date(selected.createdAt).toLocaleString()}</time></p>
            <button className="secondary-button" onClick={() => {
              const url = URL.createObjectURL(new Blob([selected.markdown], { type: 'text/markdown' }));
              const a = document.createElement('a'); a.href = url; a.download = `${selected.asOf}-${selected.id}.md`; a.click(); URL.revokeObjectURL(url);
            }}>Download Markdown</button>
          </div>
          <div className="markdown-content" dangerouslySetInnerHTML={{ __html: html }} />
        </article>
      </div>}
    </section>
  );
}
