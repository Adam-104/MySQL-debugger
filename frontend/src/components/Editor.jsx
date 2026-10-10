import { lazy, Suspense, useState } from 'react';
import { checkSchema } from '../api.js';
import { formatSql } from '../lib/format.js';

// CodeMirror is loaded on demand so the first paint stays light; a plain textarea is shown meanwhile.
const SqlEditor = lazy(() => import('./SqlEditor.jsx'));

function Fallback({ value, onChange, minHeight, ariaLabel, placeholder }) {
  return (
    <textarea
      className="editor-fallback"
      style={{ minHeight }}
      value={value}
      spellCheck={false}
      onChange={(e) => onChange(e.target.value)}
      aria-label={ariaLabel}
      placeholder={placeholder}
    />
  );
}

function Code(props) {
  return (
    <Suspense fallback={<Fallback {...props} />}>
      <SqlEditor {...props} />
    </Suspense>
  );
}

const EXAMPLE_QUERY = `SELECT * FROM invoices
WHERE status = 'PAID'
ORDER BY total DESC;`;

const SCHEMA_EXAMPLE = `CREATE TABLE users (
  id INT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(150) NOT NULL,
  created_at DATETIME NOT NULL
);
CREATE TABLE invoices (
  id INT AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL,
  total DECIMAL(10,2) NOT NULL,
  status VARCHAR(20) NOT NULL,
  INDEX idx_user (user_id)
);
INSERT INTO users (email, created_at) VALUES
  ('a@example.com', '2025-01-05 10:00:00'),
  ('b@example.com', '2025-02-11 09:30:00');
INSERT INTO invoices (user_id, total, status) VALUES
  (1, 120.50, 'PAID'), (1, 80.00, 'OPEN'), (2, 45.25, 'PAID');`;

export default function Editor({
  sql,
  onChange,
  onRun,
  loading,
  samples,
  schema,
  onSchemaChange,
  completionSchema,
  extraActions,
}) {
  const groups = [...new Set(samples.map((s) => s.group))];
  const [check, setCheck] = useState(null); // { loading, tables, error }
  const schemaOn = schema.trim().length > 0;
  const [formatNote, setFormatNote] = useState('');

  async function format() {
    try {
      onChange(await formatSql(sql));
      setFormatNote('');
    } catch (err) {
      setFormatNote(err.message);
    }
  }

  function changeSchema(value) {
    setCheck(null);
    onSchemaChange(value);
  }

  async function runCheck() {
    setCheck({ loading: true });
    try {
      setCheck({ tables: await checkSchema(schema) });
    } catch (err) {
      setCheck({ error: err.message });
    }
  }

  return (
    <section className="card editor">
      <div className="editor-bar">
        <label htmlFor="sample">Try an example</label>
        <select
          id="sample"
          value=""
          onChange={(e) => {
            const s = samples[Number(e.target.value)];
            if (s) onChange(s.sql);
          }}
        >
          <option value="" disabled>
            Choose…
          </option>
          {groups.map((g) => (
            <optgroup key={g} label={g}>
              {samples.map((s, i) =>
                s.group === g ? (
                  <option key={i} value={i}>
                    {s.label}
                  </option>
                ) : null
              )}
            </optgroup>
          ))}
        </select>
        <button className="ghost" type="button" onClick={format} disabled={!sql.trim()}>
          ✨ Format SQL
        </button>
        {formatNote && (
          <span className="hint" role="status">
            {formatNote}
          </span>
        )}
      </div>

      <Code
        value={sql}
        onChange={onChange}
        onRun={onRun}
        completionSchema={completionSchema}
        minHeight={190}
        ariaLabel="SQL statement"
        placeholder="SELECT, WITH, INSERT, UPDATE, DELETE, CREATE VIEW / TRIGGER / INDEX …"
      />

      <details className="schema-box" open={schema.trim().length > 0}>
        <summary>Use your own tables (optional)</summary>
        <p className="hint">
          Paste your <code>CREATE TABLE</code> / <code>CREATE INDEX</code> statements (for example from{' '}
          <code>SHOW CREATE TABLE</code>) and, optionally, <code>INSERT INTO</code> statements with sample rows. They
          are loaded into a temporary copy that is deleted right after the analysis, so your real database is never
          touched. With few or no rows, row counts and costs are only placeholders; more realistic rows give more
          realistic results. Leave empty to use the demo tables above.
        </p>
        <Code
          value={schema}
          onChange={changeSchema}
          minHeight={150}
          ariaLabel="Your table definitions"
          placeholder={'CREATE TABLE users (...);\nINSERT INTO users (...) VALUES (...);'}
        />
        <div className="schema-actions">
          <button className="ghost" type="button" onClick={runCheck} disabled={!schemaOn || check?.loading}>
            {check?.loading ? 'Loading…' : 'Load & check tables'}
          </button>
          <button
            className="ghost"
            type="button"
            onClick={() => {
              changeSchema(SCHEMA_EXAMPLE);
              onChange(EXAMPLE_QUERY);
            }}
          >
            Insert example (schema + query)
          </button>
          {schemaOn && (
            <button className="ghost" type="button" onClick={() => changeSchema('')}>
              Clear
            </button>
          )}
        </div>

        {check?.error && (
          <div className="alert" role="alert">
            {check.error}
          </div>
        )}
        {check?.tables && (
          <div className="check-result">
            <p className="hint">
              Loaded into a temporary database. These tables exist only while an analysis runs and are re-created every
              time you click Analyze.
            </p>
            <ul className="table-list">
              {check.tables.map((t) => (
                <li key={t.name}>
                  <div className="table-chip static">
                    <span className="t-name">{t.name}</span>
                    <span className="t-rows">{t.rows.toLocaleString()}</span>
                    <span className="t-meta">
                      {t.rows === 1 ? 'row' : 'rows'} · {t.columns} cols
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          </div>
        )}
      </details>

      {schemaOn && (
        <p className="mode-banner" role="status">
          <b>Schema mode is on.</b> Analyze runs your query against the tables you pasted only. The demo tables
          (customers, orders, …) are not available. Clear the schema box to go back to the demo database.
        </p>
      )}

      <div className="editor-actions">
        <button className="primary" onClick={onRun} disabled={loading}>
          {loading ? 'Analyzing…' : 'Analyze'}
        </button>
        <span className="hint kbd-hint">Ctrl / ⌘ + Enter</span>
        {extraActions}
      </div>
    </section>
  );
}
