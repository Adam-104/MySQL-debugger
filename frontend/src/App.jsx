import { useState } from 'react';
import { analyze } from './api.js';
import { SAMPLES } from './samples.js';
import Editor from './components/Editor.jsx';
import Overview from './components/Overview.jsx';
import FlowStepper from './components/FlowStepper.jsx';
import Issues from './components/Issues.jsx';
import IndexSuggestions from './components/IndexSuggestions.jsx';
import ExplainTable from './components/ExplainTable.jsx';
import Notes from './components/Notes.jsx';

export default function App() {
  const [sql, setSql] = useState(SAMPLES[1].sql);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function run() {
    if (!sql.trim()) {
      setError('Please enter a statement.');
      return;
    }
    setLoading(true);
    setError('');
    try {
      setResult(await analyze(sql));
    } catch (e) {
      setResult(null);
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="app">
      <header className="hero">
        <h1>🐬 MySQL Query Debugger</h1>
        <p>
          Paste a query, view, trigger or index. See how MySQL executes it step by step, why it may be slow,
          and how to fix it.
        </p>
      </header>

      <main>
        <Editor sql={sql} onChange={setSql} onRun={run} loading={loading} samples={SAMPLES} />

        {error && (
          <div className="alert" role="alert">
            {error}
          </div>
        )}

        {result && (
          <div className="results">
            <Overview result={result} />
            <div className="grid">
              <section className="card span-2">
                <h2>Execution flow</h2>
                <FlowStepper steps={result.executionFlow} />
              </section>
              <section className="card">
                <h2>
                  Optimization <small>why it matters &amp; how to fix</small>
                </h2>
                <Issues issues={result.performanceIssues} />
              </section>
              {result.indexSuggestions?.length > 0 && (
                <section className="card">
                  <h2>Suggested indexes</h2>
                  <IndexSuggestions suggestions={result.indexSuggestions} />
                </section>
              )}
              {result.executionPlan?.length > 0 && (
                <section className="card span-2">
                  <h2>MySQL EXPLAIN</h2>
                  <ExplainTable rows={result.executionPlan} />
                </section>
              )}
              {result.notes?.length > 0 && (
                <section className="card span-2">
                  <h2>Good to know</h2>
                  <Notes notes={result.notes} />
                </section>
              )}
            </div>
          </div>
        )}
      </main>

      <footer>Analysis only: your statement is never executed (only EXPLAIN is run on it).</footer>
    </div>
  );
}
