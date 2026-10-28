import { TablesSkeleton } from './Skeleton.jsx';

/** Collapsible list of the connected database's tables with row counts. Clicking one fills the editor. */
export default function TablesPanel({ tables, loading, error, onRefresh, onPick }) {
  const total = (tables || []).reduce((sum, t) => sum + t.rows, 0);

  return (
    <details className="card tables-panel">
      <summary>
        <span className="summary-title">Database tables</span>
        {tables && (
          <small>
            {tables.length} {tables.length === 1 ? 'table' : 'tables'} · {total.toLocaleString()} rows
          </small>
        )}
      </summary>

      <div className="panel-head">
        <p className="hint">Click a table to start a query. Table and column names also appear as you type.</p>
        <button className="ghost" type="button" onClick={onRefresh} disabled={loading}>
          {loading ? 'Refreshing…' : '↻ Refresh'}
        </button>
      </div>

      {loading && !tables && !error && <TablesSkeleton />}
      {error && <p className="empty">Could not load tables: {error}</p>}
      {tables && tables.length === 0 && <p className="empty">No tables found in this database.</p>}

      {tables && tables.length > 0 && (
        <ul className="table-list">
          {tables.map((t) => (
            <li key={t.name}>
              <button
                className="table-chip"
                type="button"
                onClick={() => onPick(`SELECT * FROM \`${t.name}\` LIMIT 10;`)}
                title={`Fill the editor with a SELECT on ${t.name}`}
              >
                <span className="t-name">{t.name}</span>
                <span className="t-rows">{t.rows.toLocaleString()}</span>
                <span className="t-meta">
                  {t.rows === 1 ? 'row' : 'rows'} · {t.columns} cols
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </details>
  );
}
