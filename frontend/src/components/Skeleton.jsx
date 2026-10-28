/** Placeholder blocks that hold the layout while data loads. */
export function ResultsSkeleton() {
  return (
    <div className="results" aria-busy="true" aria-label="Analysing your query">
      <div className="card sk-card">
        <div className="sk sk-title" />
        <div className="sk sk-line" />
        <div className="sk sk-line short" />
      </div>
      <div className="card sk-card">
        <div className="sk sk-tabs" />
        <div className="sk sk-block" />
      </div>
    </div>
  );
}

export function TablesSkeleton() {
  return (
    <ul className="table-list" aria-busy="true" aria-label="Loading tables">
      {[0, 1, 2, 3].map((i) => (
        <li key={i}>
          <div className="sk sk-chip" />
        </li>
      ))}
    </ul>
  );
}
