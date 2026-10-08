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

function Stat({ value, label }) {
  return (
    <div className="stat">
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}

export default function Overview({ result }) {
  const plan = result.jsonExecutionPlan;
  const rows = (result.executionPlan || []).reduce((sum, r) => sum + (r.rows || 0), 0);
  const issues = result.performanceIssues || [];
  const warnings = issues.filter((i) => i.severity === 'WARNING').length;

  return (
    <section className="card overview">
      <div className="overview-head">
        <span className="pill type">{LABELS[result.statementType] || result.statementType}</span>
        <p>{result.summary}</p>
      </div>

      <div className="stats">
        <Stat value={result.executionFlow?.length ?? 0} label="Execution steps" />
        <Stat value={issues.length} label={warnings ? `Findings (${warnings} warning${warnings > 1 ? 's' : ''})` : 'Findings'} />
        {result.executionPlan?.length > 0 && <Stat value={rows.toLocaleString()} label="Est. rows examined" />}
        {plan?.queryCost != null && <Stat value={plan.queryCost.toFixed(2)} label="Est. query cost" />}
        {plan && <Stat value={plan.orderingUsingFilesort ? 'Yes' : 'No'} label="Filesort" />}
        {plan && <Stat value={plan.groupingUsingTemporaryTable ? 'Yes' : 'No'} label="Temp table" />}
      </div>
    </section>
  );
}
