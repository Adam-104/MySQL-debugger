import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { analyze, fetchColumns, fetchTables, warmUp } from './api.js';
import { SAMPLES } from './samples.js';
import { addToHistory, clearHistory, loadHistory, removeFromHistory } from './lib/history.js';
import { buildShareUrl, decodeShare } from './lib/share.js';
import { buildCompletionSchema, parseSchemaColumns } from './lib/schemaColumns.js';
import Header from './components/Header.jsx';
import Editor from './components/Editor.jsx';
import ExampleCards from './components/ExampleCards.jsx';
import HistoryMenu from './components/HistoryMenu.jsx';
import ShareButton from './components/ShareButton.jsx';
import TablesPanel from './components/TablesPanel.jsx';
import ServerStatus from './components/ServerStatus.jsx';
import { ResultsSkeleton } from './components/Skeleton.jsx';

// The analysis view (plan, steps, fixes) is only needed after the first run, so it loads on demand.
const loadResults = () => import('./components/Results.jsx');
const Results = lazy(loadResults);

export default function App() {
  const shared = useRef(null);
  if (shared.current === null) shared.current = decodeShare(window.location.hash) || false;

  const [sql, setSql] = useState(shared.current ? shared.current.sql : '');
  const [schema, setSchema] = useState(shared.current ? shared.current.schema : '');
  const [analyzedSchema, setAnalyzedSchema] = useState('');
  const [analyzedSql, setAnalyzedSql] = useState('');
  const [result, setResult] = useState(null);
  const [tab, setTab] = useState('fixes');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [history, setHistory] = useState(() => loadHistory());

  // tables + columns of the connected database: shown in the tables panel and used for autocomplete
  const [tables, setTables] = useState(null);
  const [columns, setColumns] = useState({});
  const [tablesLoading, setTablesLoading] = useState(false);
  const [tablesError, setTablesError] = useState('');
  const tabsRef = useRef(null);

  const loadDb = useCallback(async () => {
    setTablesLoading(true);
    setTablesError('');
    try {
      const [t, c] = await Promise.all([fetchTables(), fetchColumns()]);
      setTables(t);
      setColumns(c);
    } catch (e) {
      setTablesError(e.message);
    } finally {
      setTablesLoading(false);
    }
  }, []);

  useEffect(() => {
    warmUp(); // wakes a sleeping free-tier backend while the visitor reads the page
    loadDb();
    // fetch the results code while the browser is idle, so the first analysis shows instantly
    const idle = window.requestIdleCallback || ((fn) => setTimeout(fn, 1500));
    idle(() => loadResults().catch(() => {}));
  }, [loadDb]);

  const completionSchema = useMemo(
    () => buildCompletionSchema(tables || [], columns, parseSchemaColumns(schema)),
    [tables, columns, schema]
  );

  const run = useCallback(
    async (nextSql, nextSchema) => {
      if (!nextSql.trim()) {
        setError('Please enter a statement.');
        return;
      }
      setLoading(true);
      setError('');
      try {
        const analysis = await analyze(nextSql, nextSchema);
        setResult(analysis);
        setAnalyzedSchema(nextSchema);
        setAnalyzedSql(nextSql);
        setTab('fixes');
        setHistory(addToHistory({ sql: nextSql, schema: nextSchema, type: analysis.statementType }));

        // keep the address bar in sync, so the URL can simply be copied
        const { url, tooLong } = buildShareUrl(
          { origin: window.location.origin, pathname: window.location.pathname },
          { sql: nextSql, schema: nextSchema }
        );
        window.history.replaceState(null, '', tooLong ? window.location.pathname : url);
      } catch (e) {
        setResult(null);
        setError(e.message);
      } finally {
        setLoading(false);
      }
    },
    []
  );

  // opening a share link runs the analysis straight away (guarded: React StrictMode runs effects twice in dev)
  const autoRan = useRef(false);
  useEffect(() => {
    if (shared.current && !autoRan.current) {
      autoRan.current = true;
      run(shared.current.sql, shared.current.schema);
    }
  }, [run]);

  function runExample(sample) {
    setSql(sample.sql);
    setSchema(''); // examples use the demo tables
    run(sample.sql, '');
  }

  function openHistory(entry) {
    setSql(entry.sql);
    setSchema(entry.schema || '');
    run(entry.sql, entry.schema || '');
  }

  function openFixes() {
    setTab('fixes');
    tabsRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }

  return (
    <div className="app">
      <Header />
      <ServerStatus />

      <main>
        <Editor
          sql={sql}
          onChange={setSql}
          onRun={() => run(sql, schema)}
          loading={loading}
          samples={SAMPLES}
          schema={schema}
          onSchemaChange={setSchema}
          completionSchema={completionSchema}
          extraActions={
            <>
              <HistoryMenu
                items={history}
                onPick={openHistory}
                onRemove={(id) => setHistory(removeFromHistory(id))}
                onClear={() => setHistory(clearHistory())}
              />
              <ShareButton sql={sql} schema={schema} disabled={loading} />
            </>
          }
        />

        {error && (
          <div className="alert" role="alert">
            {error}
          </div>
        )}

        {!result && !loading && !error && <ExampleCards samples={SAMPLES} onPick={runExample} disabled={loading} />}

        {loading && !result && <ResultsSkeleton />}

        {result && (
          <Suspense fallback={<ResultsSkeleton />}>
            <div className={loading ? 'is-refreshing' : undefined}>
              <Results
                result={result}
                analyzedSql={analyzedSql}
                analyzedSchema={analyzedSchema}
                tab={tab}
                onTab={setTab}
                onOpenFixes={openFixes}
                tabsRef={tabsRef}
              />
            </div>
          </Suspense>
        )}

        <TablesPanel
          tables={tables}
          loading={tablesLoading}
          error={tablesError}
          onRefresh={loadDb}
          onPick={setSql}
        />
      </main>

      <footer>Analysis only: your statement is never executed (only EXPLAIN is run on it).</footer>
    </div>
  );
}
