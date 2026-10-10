import { useEffect, useState } from 'react';
import { whatIf } from '../api.js';
import { compareWhatIf } from '../planModel.js';

const TREND = {
  better: { cls: 'good', icon: '✓', label: 'Better' },
  worse: { cls: 'bad', icon: '✕', label: 'Worse' },
  same: { cls: 'plain', icon: '–', label: 'Same' },
};
const VERDICT_ICON = { good: '✓', bad: '✕', meh: '!', info: 'i' };

function CostBars({ before, after }) {
  if (before == null || after == null) return null;
  const max = Math.max(before, after, 1);
  const rows = [
    { name: 'Before', value: before, cls: 'is-before' },
    { name: 'After', value: after, cls: '' },
  ];
  return (
    <ul className="funnel-list cost-bars" aria-label="Estimated query cost before and after">
      {rows.map((r) => (
        <li key={r.name} className={`funnel-row ${r.cls}`} title={`${r.name}: estimated cost ${r.value}`}>
          <span className="funnel-label">{r.name} (estimated cost)</span>
          <span className="funnel-track">
            <span className="funnel-bar" style={{ width: `${Math.max((r.value / max) * 100, 0.8)}%` }} />
          </span>
          <span className="funnel-val">{Number(r.value).toLocaleString(undefined, { maximumFractionDigits: 1 })}</span>
        </li>
      ))}
    </ul>
  );
}

function WhatIfResult({ data }) {
  const cmp = compareWhatIf(data.before, data.after);
  if (!cmp) {
    return <p className="hint">This query has no table plan to compare.</p>;
  }
  const v = cmp.verdict;
  return (
    <div className="whatif">
      <p className={`verdict tone-${v.tone}`} role="status">
        <span aria-hidden="true">{VERDICT_ICON[v.tone]}</span> <b>{v.title}.</b> {v.text}
      </p>
      <div className="scroll">
        <table className="cmp">
          <thead>
            <tr>
              <th>What</th>
              <th>Before</th>
              <th>After</th>
              <th>Change</th>
            </tr>
          </thead>
          <tbody>
            {cmp.rows.map((r, i) => {
              const t = TREND[r.trend];
              return (
                <tr key={i}>
                  <td className="wrap">{r.label}</td>
                  <td className="wrap" data-label="Before">{r.before}</td>
                  <td className="wrap" data-label="After">{r.after}</td>
                  <td>
                    <span className={`badge ${t.cls}`}>
                      <span aria-hidden="true">{t.icon}</span> {t.label}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <CostBars before={cmp.costBefore} after={cmp.costAfter} />
      <p className="hint">
        Tried on {data.copyOfDatabase ? 'a temporary copy of the database' : 'a temporary copy built from your pasted schema'}.
        Your real database was not changed. Numbers are MySQL's estimates.
      </p>
    </div>
  );
}

export default function IndexSuggestions({ suggestions, query, schema }) {
  const [copied, setCopied] = useState(-1);
  const [runs, setRuns] = useState({}); // suggestion index -> { loading | error | data }

  useEffect(() => {
    setRuns({});
  }, [suggestions]);

  async function copy(text, i) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(i);
      setTimeout(() => setCopied(-1), 1500);
    } catch {
      /* clipboard may be unavailable (http); ignore */
    }
  }

  async function compare(i, ddl) {
    setRuns((r) => ({ ...r, [i]: { loading: true } }));
    try {
      const data = await whatIf(query, schema, ddl);
      setRuns((r) => ({ ...r, [i]: { data } }));
    } catch (e) {
      setRuns((r) => ({ ...r, [i]: { error: e.message } }));
    }
  }

  return (
    <div className="suggestions">
      {suggestions.map((s, i) => {
        const run = runs[i];
        return (
          <article key={i} className="suggestion">
            <div className="ddl">
              <pre>{s.ddl}</pre>
              <button className="ghost" onClick={() => copy(s.ddl, i)}>
                {copied === i ? 'Copied ✓' : 'Copy'}
              </button>
            </div>
            <p>{s.reason}</p>
            <div className="suggestion-actions">
              <button className="ghost" onClick={() => compare(i, s.ddl)} disabled={run?.loading || !query}>
                {run?.loading ? 'Comparing…' : 'What if I add this index?'}
              </button>
            </div>
            {run?.error && (
              <div className="alert" role="alert">
                {run.error}
              </div>
            )}
            {run?.data && <WhatIfResult data={run.data} />}
            <p className="caveat">Test on a copy first: every index also slows down writes and uses disk space.</p>
          </article>
        );
      })}
    </div>
  );
}
