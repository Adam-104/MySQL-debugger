import { buildPlan, fmt } from '../planModel.js';

function Node({ n }) {
  const unusedIndex = !n.key && n.possibleKeys;
  return (
    <li className={`plan-node tone-${n.access.tone}`}>
      <div className="plan-node-head">
        <b className="plan-table">{n.table || '(no table)'}</b>
        {n.selectType && n.selectType !== 'SIMPLE' && <span className="tag">{n.selectType.toLowerCase()}</span>}
      </div>
      <span className={`badge ${n.access.tone === 'neutral' ? 'info' : n.access.tone}`} title={n.access.hint}>
        <span aria-hidden="true">{n.access.icon}</span> {n.access.label}
      </span>
      <p className="plan-line">{n.key ? <>Index: <code>{n.key}</code></> : 'No index used'}</p>
      {unusedIndex && (
        <p className="plan-line muted">
          Candidates not used: <code>{n.possibleKeys}</code>
        </p>
      )}
      {n.rows != null && (
        <p className="plan-line">
          ≈ <b>{fmt(n.rows)}</b> {n.rows === 1 ? 'row' : 'rows'}
          {n.filtered != null && <span className="muted"> · {Math.round(n.filtered)}% kept</span>}
        </p>
      )}
      {n.chips.length > 0 && (
        <div className="plan-chips">
          {n.chips.map((c, i) => (
            <span key={i} className={`chip tone-${c.tone}`}>
              {c.label}
            </span>
          ))}
        </div>
      )}
    </li>
  );
}

function Arrow({ text }) {
  return (
    <li className="plan-arrow" aria-hidden="true">
      <span className="arrow-h">→</span>
      <span className="arrow-v">↓</span>
      <small>{text}</small>
    </li>
  );
}

function Diagram({ model }) {
  return (
    <div className="plan-groups">
      {model.groups.map((g, gi) => {
        const items = [];
        g.nodes.forEach((n, i) => {
          if (i > 0) items.push(<Arrow key={`a${i}`} text="for each row" />);
          items.push(<Node key={`n${i}`} n={n} />);
        });
        if (gi === 0) {
          model.post.forEach((p, i) => {
            items.push(<Arrow key={`pa${i}`} text="then" />);
            items.push(
              <li key={`p${i}`} className={`plan-node tone-${p.tone}`}>
                <b className="plan-table">{p.label}</b>
                <span className={`badge ${p.tone}`}>
                  <span aria-hidden="true">{p.icon}</span> Extra work
                </span>
                <p className="plan-line">{p.detail}</p>
              </li>
            );
          });
          items.push(<Arrow key="ra" text="then" />);
          items.push(
            <li key="res" className="plan-node tone-neutral plan-result">
              <b className="plan-table">Result</b>
              <p className="plan-line">rows returned to you</p>
            </li>
          );
        }
        return (
          <div key={g.id ?? gi} className="plan-group">
            <h3>{g.title}</h3>
            <ol className="plan-flow">{items}</ol>
          </div>
        );
      })}
    </div>
  );
}

function Funnel({ funnel }) {
  const max = Math.max(...funnel.stages.map((s) => s.value), 1);
  return (
    <div className="funnel">
      <h3>
        Estimated rows at each step <small>MySQL's estimates from table statistics, not real counts</small>
      </h3>
      <ul className="funnel-list">
        {funnel.stages.map((s, i) => {
          const pct = Math.max((s.value / max) * 100, 0.8);
          return (
            <li
              key={i}
              className="funnel-row"
              tabIndex={0}
              title={`${s.label}: about ${fmt(s.value)} rows (${s.detail})`}
            >
              <span className="funnel-label">{s.label}</span>
              <span className="funnel-track">
                <span className="funnel-bar" style={{ width: `${pct}%` }} />
              </span>
              <span className="funnel-val">{fmt(s.value)}</span>
            </li>
          );
        })}
      </ul>
      {funnel.worst && (
        <p className="funnel-callout">
          Most wasted work: <b>{funnel.worst.table}</b> reads about {fmt(funnel.worst.examined)} rows to keep about{' '}
          {fmt(funnel.worst.kept)} ({funnel.worst.pct}%). An index on the filtered columns could cut the reads.
        </p>
      )}
      {funnel.note && <p className="hint">{funnel.note}</p>}
      <details className="viz-table">
        <summary>Show as table</summary>
        <div className="scroll">
          <table>
            <thead>
              <tr>
                <th>Step</th>
                <th>Rows (est.)</th>
                <th>Note</th>
              </tr>
            </thead>
            <tbody>
              {funnel.stages.map((s, i) => (
                <tr key={i}>
                  <td>{s.label}</td>
                  <td>{fmt(s.value)}</td>
                  <td>{s.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}

export default function VisualPlan({ plan, json, flow }) {
  const model = buildPlan(plan, json, flow);
  if (!model) return null;
  return (
    <section className="card visual-plan">
      <h2>
        Visual plan <small>how MySQL will read your tables</small>
      </h2>
      <Diagram model={model} />
      {model.funnel && <Funnel funnel={model.funnel} />}
    </section>
  );
}
