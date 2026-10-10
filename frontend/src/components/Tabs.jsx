import { useRef } from 'react';

/**
 * Accessible tabs (WAI-ARIA tabs pattern): arrow keys / Home / End move between tabs, only the active panel is
 * rendered.  tabs = [{ id, label, badge?, content }]
 */
export default function Tabs({ tabs, active, onChange, idPrefix = 'tabs' }) {
  const buttons = useRef({});

  function onKeyDown(e, index) {
    let next = null;
    if (e.key === 'ArrowRight') next = (index + 1) % tabs.length;
    else if (e.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = tabs.length - 1;
    if (next === null) return;
    e.preventDefault();
    onChange(tabs[next].id);
    buttons.current[tabs[next].id]?.focus();
  }

  const current = tabs.find((t) => t.id === active) || tabs[0];

  return (
    <section className="card tabs-card">
      <div className="tablist" role="tablist" aria-label="Analysis details">
        {tabs.map((t, i) => {
          const selected = t.id === current.id;
          return (
            <button
              key={t.id}
              ref={(el) => { buttons.current[t.id] = el; }}
              id={`${idPrefix}-tab-${t.id}`}
              role="tab"
              type="button"
              aria-selected={selected}
              aria-controls={`${idPrefix}-panel-${t.id}`}
              tabIndex={selected ? 0 : -1}
              className={`tab ${selected ? 'active' : ''}`}
              onClick={() => onChange(t.id)}
              onKeyDown={(e) => onKeyDown(e, i)}
            >
              {t.label}
              {t.badge != null && <span className="tab-badge">{t.badge}</span>}
            </button>
          );
        })}
      </div>
      <div
        id={`${idPrefix}-panel-${current.id}`}
        role="tabpanel"
        aria-labelledby={`${idPrefix}-tab-${current.id}`}
        className="tabpanel"
        tabIndex={0}
      >
        {current.content}
      </div>
    </section>
  );
}
