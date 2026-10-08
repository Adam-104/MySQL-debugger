import { useState } from 'react';

export default function IndexSuggestions({ suggestions }) {
  const [copied, setCopied] = useState(-1);

  async function copy(text, i) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(i);
      setTimeout(() => setCopied(-1), 1500);
    } catch {
      /* clipboard may be unavailable (http); ignore */
    }
  }

  return (
    <div className="suggestions">
      {suggestions.map((s, i) => (
        <article key={i} className="suggestion">
          <div className="ddl">
            <pre>{s.ddl}</pre>
            <button className="ghost" onClick={() => copy(s.ddl, i)}>
              {copied === i ? 'Copied ✓' : 'Copy'}
            </button>
          </div>
          <p>{s.reason}</p>
          <p className="caveat">
            Test on a copy first: every index also slows down writes and uses disk space.
          </p>
        </article>
      ))}
    </div>
  );
}
