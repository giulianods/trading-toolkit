import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { fetchDescription } from '../api';

export default function CompanyDescription({ market, ticker }) {
  const [open, setOpen] = useState(false);
  const [description, setDescription] = useState(null);
  const [failed, setFailed] = useState(false);
  const [position, setPosition] = useState({});
  const buttonRef = useRef(null);
  const closeTimer = useRef(null);
  const tooltipId = useId();

  const show = () => {
    window.clearTimeout(closeTimer.current);
    setOpen(true);
  };
  const hide = () => {
    closeTimer.current = window.setTimeout(() => setOpen(false), 120);
  };

  useEffect(() => () => window.clearTimeout(closeTimer.current), []);

  useEffect(() => {
    if (!open || description !== null) return undefined;
    const controller = new AbortController();
    setFailed(false);
    fetchDescription(market, ticker, controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) setDescription(data?.description?.trim() || 'Company description is not available.');
      })
      .catch(() => {
        if (!controller.signal.aborted) setFailed(true);
      });
    return () => controller.abort();
  }, [open, description, market, ticker]);

  useLayoutEffect(() => {
    if (!open) return undefined;
    const updatePosition = () => {
      const rect = buttonRef.current.getBoundingClientRect();
      const width = Math.min(360, window.innerWidth - 24);
      const below = window.innerHeight - rect.bottom - 20;
      const above = rect.top - 20;
      const useBelow = below >= 180 || below >= above;
      setPosition({
        width,
        left: Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12)),
        ...(useBelow ? { top: rect.bottom + 8 } : { bottom: window.innerHeight - rect.top + 8 }),
        maxHeight: Math.min(320, Math.max(0, useBelow ? below : above)),
      });
    };
    const onKeyDown = (event) => {
      if (event.key === 'Escape') setOpen(false);
    };
    updatePosition();
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className="chart-description-button"
        title=""
        aria-label={`Company description for ${ticker}`}
        aria-describedby={open ? tooltipId : undefined}
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={show}
        onBlur={hide}
        onClick={show}
      >
        <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <circle cx="10" cy="10" r="7.5" stroke="currentColor" strokeWidth="1.5" />
          <path d="M10 9v5" stroke="currentColor" strokeWidth="1.5" />
          <circle cx="10" cy="6" r="1" fill="currentColor" />
        </svg>
      </button>
      {open && createPortal(
        <div
          id={tooltipId}
          role="tooltip"
          className="chart-description-tooltip"
          style={position}
          onMouseEnter={show}
          onMouseLeave={hide}
          onClick={(event) => event.stopPropagation()}
          onDoubleClick={(event) => event.stopPropagation()}
        >
          <strong>{ticker} · Company description</strong>
          <p>{failed ? 'Unable to load the company description. Hover again to retry.' : description ?? 'Loading company description…'}</p>
        </div>,
        document.body
      )}
    </>
  );
}
