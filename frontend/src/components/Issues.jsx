const ICON = { WARNING: '⚠️', NOTICE: '🟠', INFO: 'ℹ️' };

export default function Issues({ issues = [] }) {
  if (!issues.length) {
    return <p className="empty">No obvious problems found. 🎉</p>;
  }

  return (
    <div className="issues">
      {issues.map((i, idx) => (
        <article key={idx} className={`issue ${i.severity}`}>
          <h3>
            <span>{ICON[i.severity] || '•'}</span> {i.title}
            {i.table && <code className="tag">{i.table}</code>}
            {i.estimatedRows > 0 && <code className="tag">~{i.estimatedRows.toLocaleString()} rows</code>}
          </h3>
          {i.description && <p>{i.description}</p>}
          {i.relatedExpression && (
            <p className="related">
              {i.relatedOperation}: <code>{i.relatedExpression}</code>
            </p>
          )}
          {i.reason && (
            <p className="reason">
              <b>Why it matters:</b> {i.reason}
            </p>
          )}
          {i.suggestion && (
            <p className="fix">
              <b>How to fix:</b> {i.suggestion}
            </p>
          )}
        </article>
      ))}
    </div>
  );
}
