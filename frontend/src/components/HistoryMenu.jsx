import { useEffect, useRef, useState } from 'react';
import { previewSql, timeAgo } from '../lib/history.js';

/** "History" button with a popover listing the last queries (stored in this browser only). */
export default function HistoryMenu({ items, onPick, onRemove, onClear }) {
  const [open, setOpen] = useState(false);
  const root = useRef(null);

  useEffect(() => {
    if (!open) return undefined;
    const onDown = (e) => { if (root.current && !root.current.contains(e.target)) setOpen(false); };
    const onKey = (e) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="history" ref={root}>
      <button
        type="button"
        className="ghost"
        aria-expanded={open}
        aria-controls="history-panel"
        onClick={() => setOpen((o) => !o)}
      >
        🕘 History{items.length > 0 && <span className="count">{items.length}</span>}
      </button>

      {open && (
        <div id="history-panel" className="history-panel" role="region" aria-label="Recent queries">
          {items.length === 0 ? (
            <p className="empty">No queries yet. Analyzed queries appear here (saved in this browser only).</p>
          ) : (
            <>
              <ul>
                {items.map((e) => (
                  <li key={e.id}>
                    <button
                      type="button"
                      className="history-item"
                      onClick={() => { setOpen(false); onPick(e); }}
                      title={e.sql}
                    >
                      <code>{previewSql(e.sql)}</code>
                      <span className="history-meta">
                        {e.type && <span className="pill type">{e.type.replace('_', ' ')}</span>}
                        {e.schema && <span className="pill">own tables</span>}
                        {timeAgo(e.savedAt)}
                      </span>
                    </button>
                    <button
                      type="button"
                      className="icon-btn"
                      aria-label={`Remove from history: ${previewSql(e.sql, 40)}`}
                      onClick={() => onRemove(e.id)}
                    >
                      ✕
                    </button>
                  </li>
                ))}
              </ul>
              <button type="button" className="ghost" onClick={onClear}>Clear history</button>
            </>
          )}
        </div>
      )}
    </div>
  );
}
