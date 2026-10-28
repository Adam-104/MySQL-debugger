import Verdict from './Verdict.jsx';
import Tabs from './Tabs.jsx';
import VisualPlan from './VisualPlan.jsx';
import FlowStepper from './FlowStepper.jsx';
import Issues from './Issues.jsx';
import IndexSuggestions from './IndexSuggestions.jsx';
import ExplainTable from './ExplainTable.jsx';
import Notes from './Notes.jsx';

/** Everything shown after an analysis. Loaded on demand (App lazy-imports it) so the first page load stays small. */
export default function Results({ result, analyzedSql, analyzedSchema, tab, onTab, onOpenFixes, tabsRef }) {
  const flowQuery = result.query || analyzedSql;

  const tabs = [
    result.executionPlan?.length > 0 && {
      id: 'plan',
      label: 'Visual plan',
      content: <VisualPlan plan={result.executionPlan} json={result.jsonExecutionPlan} flow={result.executionFlow} />,
    },
    {
      id: 'steps',
      label: 'Steps',
      badge: result.executionFlow?.length || 0,
      content: <FlowStepper steps={result.executionFlow} query={flowQuery} />,
    },
    {
      id: 'fixes',
      label: 'Fixes',
      badge: (result.performanceIssues?.length || 0) + (result.indexSuggestions?.length || 0),
      content: (
        <div className="fixes">
          <Issues issues={result.performanceIssues} />
          {result.indexSuggestions?.length > 0 && (
            <>
              <h3 className="subhead">Suggested indexes</h3>
              <IndexSuggestions suggestions={result.indexSuggestions} query={flowQuery} schema={analyzedSchema} />
            </>
          )}
        </div>
      ),
    },
    result.executionPlan?.length > 0 && {
      id: 'explain',
      label: 'EXPLAIN',
      content: <ExplainTable rows={result.executionPlan} />,
    },
    result.notes?.length > 0 && { id: 'notes', label: 'Notes', content: <Notes notes={result.notes} /> },
  ].filter(Boolean);

  return (
    <div className="results">
      <Verdict result={result} onOpenFixes={onOpenFixes} />
      <div ref={tabsRef} className="tabs-anchor">
        <Tabs tabs={tabs} active={tab} onChange={onTab} />
      </div>
    </div>
  );
}
