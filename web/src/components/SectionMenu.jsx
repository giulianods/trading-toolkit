import React, { useEffect, useRef } from 'react';

export default function SectionMenu({ section, onSelect, onClose }) {
  const panel = useRef(null);
  useEffect(() => {
    const previousFocus = document.activeElement;
    panel.current.querySelector('button').focus();
    const onKey = (event) => {
      if (event.key === 'Escape') onClose();
      if (event.key === 'Tab') {
        const buttons = [...panel.current.querySelectorAll('button')];
        const first = buttons[0];
        const last = buttons[buttons.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); previousFocus?.focus(); };
  }, [onClose]);
  return (
    <div className="section-menu-overlay" onClick={onClose}>
      <aside ref={panel} id="section-menu" className="section-menu" role="dialog" aria-modal="true" aria-label="Navigation" onClick={(e) => e.stopPropagation()}>
        <div className="section-menu-heading"><strong>Trading Toolkit</strong><button onClick={onClose} aria-label="Close menu">×</button></div>
        <nav aria-label="Sections">
          {['Screens', 'Reports', 'Portfolio'].map((name, index) => (
            <button key={name} aria-current={section === name ? 'page' : undefined} onClick={() => { onSelect(name); onClose(); }}>
              <span className="section-menu-number">0{index + 1}</span>{name}
            </button>
          ))}
        </nav>
        <p>Green Sheep · Trading Toolkit</p>
      </aside>
    </div>
  );
}
