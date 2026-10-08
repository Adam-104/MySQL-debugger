const COLUMNS = [
  ['id', 'id'],
  ['selectType', 'select_type'],
  ['table', 'table'],
  ['type', 'type'],
  ['possibleKeys', 'possible_keys'],
  ['key', 'key'],
  ['keyLength', 'key_len'],
  ['ref', 'ref'],
  ['rows', 'rows'],
  ['filtered', 'filtered %'],
  ['extra', 'Extra'],
];

function badge(type) {
  const t = (type || '').toLowerCase();
  const cls = t === 'all' ? 'bad' : t === 'index' ? 'meh' : ['const', 'system', 'eq_ref'].includes(t) ? 'good' : 'info';
  return <span className={`badge ${cls}`}>{type || '–'}</span>;
}

export default function ExplainTable({ rows }) {
  return (
    <div className="scroll">
      <table>
        <thead>
          <tr>
            {COLUMNS.map(([, label]) => (
              <th key={label}>{label}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              {COLUMNS.map(([key]) => (
                <td key={key} className={key === 'extra' ? 'wrap' : ''}>
                  {key === 'type' ? badge(r.type) : <code>{r[key] ?? '–'}</code>}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <p className="legend">
        <span className="badge bad">ALL</span> full table scan · <span className="badge meh">index</span> full index scan ·{' '}
        <span className="badge good">const / eq_ref</span> single-row lookup
      </p>
    </div>
  );
}
