import { useState } from 'react';
import { buildShareUrl } from '../lib/share.js';

/** Copies a link that re-opens this query. The query travels in the URL fragment (#q=…), never to a server. */
export default function ShareButton({ sql, schema, disabled }) {
  const [status, setStatus] = useState('');

  async function share() {
    const { url, tooLong } = buildShareUrl(
      { origin: window.location.origin, pathname: window.location.pathname },
      { sql, schema }
    );
    if (tooLong) {
      setStatus('This query is too long for a link. Copy the SQL instead.');
      return;
    }
    window.history.replaceState(null, '', url);
    try {
      await navigator.clipboard.writeText(url);
      setStatus('Link copied ✓');
    } catch {
      setStatus('Could not copy automatically. The link is now in your address bar.');
    }
    setTimeout(() => setStatus(''), 3500);
  }

  return (
    <span className="share">
      <button type="button" className="ghost" onClick={share} disabled={disabled || !sql.trim()}>
        🔗 Share link
      </button>
      <span className="hint" role="status" aria-live="polite">{status}</span>
    </span>
  );
}
