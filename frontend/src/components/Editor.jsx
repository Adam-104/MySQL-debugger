export default function Editor({ sql, onChange, onRun, loading, samples }) {
  const groups = [...new Set(samples.map((s) => s.group))];

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
      </div>

      <textarea
        value={sql}
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') onRun();
        }}
        placeholder="SELECT, WITH, INSERT, UPDATE, DELETE, CREATE VIEW / TRIGGER / INDEX …"
        aria-label="SQL statement"
      />

      <div className="editor-actions">
        <button className="primary" onClick={onRun} disabled={loading}>
          {loading ? 'Analyzing…' : 'Analyze'}
        </button>
        <span className="hint">Ctrl / ⌘ + Enter</span>
      </div>
    </section>
  );
}
