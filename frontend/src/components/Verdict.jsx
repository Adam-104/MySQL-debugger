import { useState } from 'react';
import { buildVerdict } from '../lib/verdict.js';

const LABELS = {
  SELECT: 'SELECT query',
  CTE_SELECT: 'SELECT with CTE',
  INSERT: 'INSERT',
  REPLACE: 'REPLACE',
  UPDATE: 'UPDATE',
  DELETE: 'DELETE',
  CREATE_VIEW: 'CREATE VIEW',
  CREATE_TRIGGER: 'CREATE TRIGGER',
  CREATE_INDEX: 'CREATE INDEX',
};
const ICON = { bad: '⚠️', meh: '🟠', info: 'ℹ️', good: '✅' };

function Stat({ value, label }) {
  return (
    <div className="stat">
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}

/** The answer first: one verdict, the biggest problem and the top fix. Details live in the tabs below. */
export default function Verdict({ result, onOpenFixes }) {
  const [copied, setCopied] = useState(false);
  const v = buildVerdict(result);
  const plan = result.jsonExecutionPlan;
  const rows = (result.executionPlan || []).reduce((sum, r) => sum + (r.rows || 0), 0);

  async function copyDdl() {
    try {
      await navigator.clipboard.writeText(v.topFix.ddl);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable (e.g. http): the text stays visible and selectable */
    }
  }

  return (
    <section className={`card verdict tone-${v.tone}`} aria-labelledby="verdict-title">
      <div className="verdict-head">
        <span className="verdict-icon" aria-hidden="true">{ICON[v.tone]}</span>
        <div className="verdict-text">
          <h2 id="verdict-title">{v.headline}</h2>
          <p className="verdict-summary">
            <span className="pill type">{LABELS[result.statementType] || result.statementType}</span> {result.summary}
          </p>
        </div>
      </div>

      {(v.biggest || v.topFix) && (
        <div className="verdict-body">
          {v.biggest && (
            <div className="verdict-block">
              <span className="verdict-label">Biggest problem</span>
              <p>{v.biggest.text}</p>
            </div>
          )}
          {v.topFix && (
            <div className="verdict-block fix">
              <span className="verdict-label">Top fix</span>
              {v.topFix.kind === 'index' ? (
                <div className="ddl">
                  <pre>{v.topFix.ddl}</pre>
                  <button className="ghost" type="button" onClick={copyDdl}>
                    {copied ? 'Copied ✓' : 'Copy'}
                  </button>
                </div>
              ) : (
                <p>{v.topFix.text}</p>
              )}
            </div>
          )}
        </div>
      )}

      <div className="verdict-foot">
        <div className="stats">
          <Stat value={result.executionFlow?.length ?? 0} label="Execution steps" />
          {result.executionPlan?.length > 0 && <Stat value={rows.toLocaleString()} label="Est. rows examined" />}
          {plan?.queryCost != null && <Stat value={plan.queryCost.toFixed(2)} label="Est. query cost" />}
          {plan && <Stat value={plan.orderingUsingFilesort ? 'Yes' : 'No'} label="Filesort" />}
          {plan && <Stat value={plan.groupingUsingTemporaryTable ? 'Yes' : 'No'} label="Temp table" />}
        </div>
        {(v.counts.warning + v.counts.notice + v.counts.info > 0 || v.indexCount > 0) && (
          <button className="ghost" type="button" onClick={onOpenFixes}>
            See all fixes →
          </button>
        )}
      </div>
    </section>
  );
}
