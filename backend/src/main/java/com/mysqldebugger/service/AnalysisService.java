package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import com.mysqldebugger.model.ColumnInfo;
import com.mysqldebugger.model.DebugResponse;
import com.mysqldebugger.model.ExplainResult;
import com.mysqldebugger.model.ExplainerResult;
import com.mysqldebugger.model.IndexInfo;
import com.mysqldebugger.model.IndexSuggestion;
import com.mysqldebugger.model.JsonExecutionPlan;
import com.mysqldebugger.model.PerformanceIssue;
import com.mysqldebugger.model.StatementType;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates a full analysis: validate → classify → explain the statement type →
 * (when MySQL can explain it) EXPLAIN → performance issues → index suggestions.
 *
 * Only SELECT / DML text and the body of a view are ever sent to MySQL, and only
 * behind EXPLAIN. CREATE TRIGGER / CREATE INDEX / CREATE VIEW statements are never executed.
 */
@Service
public class AnalysisService {

    private final QueryValidator validator;
    private final StatementClassifier classifier;
    private final SelectExplainer selectExplainer;
    private final DmlExplainer dmlExplainer;
    private final ViewExplainer viewExplainer;
    private final TriggerExplainer triggerExplainer;
    private final IndexExplainer indexExplainer;
    private final IndexSuggester indexSuggester;
    private final PerformanceAnalyzer performanceAnalyzer;
    private final DebugService debugService;
    private final SchemaSandboxService sandbox;

    public AnalysisService(
            QueryValidator validator,
            StatementClassifier classifier,
            SelectExplainer selectExplainer,
            DmlExplainer dmlExplainer,
            ViewExplainer viewExplainer,
            TriggerExplainer triggerExplainer,
            IndexExplainer indexExplainer,
            IndexSuggester indexSuggester,
            PerformanceAnalyzer performanceAnalyzer,
            DebugService debugService,
            SchemaSandboxService sandbox) {

        this.validator = validator;
        this.classifier = classifier;
        this.selectExplainer = selectExplainer;
        this.dmlExplainer = dmlExplainer;
        this.viewExplainer = viewExplainer;
        this.triggerExplainer = triggerExplainer;
        this.indexExplainer = indexExplainer;
        this.indexSuggester = indexSuggester;
        this.performanceAnalyzer = performanceAnalyzer;
        this.debugService = debugService;
        this.sandbox = sandbox;
    }

    /**
     * Analyses {@code rawSql}. When {@code schema} (CREATE TABLE / CREATE INDEX statements) is given, the query is
     * analysed against an empty temporary copy of that schema instead of the configured database.
     */
    public DebugResponse analyze(String rawSql, String schema) {
        if (schema == null || schema.isBlank()) {
            return analyze(rawSql);
        }
        validator.validate(rawSql); // fail fast before creating anything
        DebugResponse response = sandbox.withSandbox(schema, () -> analyze(rawSql));
        boolean withRows = SchemaScript.parse(schema).stream().anyMatch(SchemaScript::isInsert);
        response.getNotes().add(0, withRows
                ? "Analysed against a temporary copy of your schema loaded with the sample rows you pasted. With only a few "
                + "rows MySQL often prefers a full scan even when an index exists (it is cheaper for tiny tables), so "
                + "judge index usage and costs with realistic row counts."
                : "Analysed against an empty temporary copy of the schema you pasted. Access types, index choices and "
                + "ordering/grouping steps are real, but row counts and cost figures are placeholders because the "
                + "tables hold no data. Paste INSERT statements to add sample rows.");
        return response;
    }

    public DebugResponse analyze(String rawSql) {

        String sql = validator.validate(rawSql);
        StatementType type = classifier.classify(sql);

        ExplainerResult r = switch (type) {
            case SELECT, CTE_SELECT -> selectExplainer.explain(sql);
            case INSERT, REPLACE, UPDATE, DELETE -> dmlExplainer.explain(sql, type);
            case CREATE_VIEW -> viewExplainer.explain(sql);
            case CREATE_TRIGGER -> triggerExplainer.explain(sql);
            case CREATE_INDEX -> explainIndex(sql);
            default -> throw new InvalidQueryException(
                    "Unsupported statement. Supported: SELECT, WITH ... SELECT, INSERT, REPLACE, UPDATE, DELETE, "
                            + "CREATE VIEW, CREATE TRIGGER, CREATE INDEX and ALTER TABLE ... ADD INDEX.");
        };

        List<ExplainResult> plan = List.of();
        JsonExecutionPlan json = null;

        if (r.explainSql != null) {
            plan = debugService.debugQuery(r.explainSql);
            try {
                json = debugService.debugJsonQuery(r.explainSql);
            } catch (DataAccessException | IllegalArgumentException e) {
                json = null; // the tabular plan is enough
            }

            String analysisSql = r.analysisSql != null ? r.analysisSql : sql;
            List<PerformanceIssue> planIssues = performanceAnalyzer.analyze(analysisSql, plan, r.flow, json);
            if (type == StatementType.CREATE_VIEW) {
                planIssues = planIssues.stream()
                        .filter(i -> !"SELECT * Used".equals(i.getTitle()))
                        .toList();
            }
            r.issues.addAll(planIssues);
        }

        List<IndexSuggestion> suggestions = r.explainSql != null
                ? indexSuggester.suggest(plan, r.flow, debugService::existingIndexes)
                : List.of();

        addTriggerNote(r, type);

        r.issues.sort(Comparator.comparingInt(i -> severityRank(i.getSeverity())));

        DebugResponse response = new DebugResponse(sql, r.flow, plan, json, r.issues);
        response.setStatementType(type);
        response.setSummary(r.summary);
        response.setNotes(r.notes);
        response.setIndexSuggestions(suggestions);
        return response;
    }

    private ExplainerResult explainIndex(String sql) {
        IndexExplainer.Definition def = indexExplainer.parse(sql);
        List<IndexInfo> existing = debugService.existingIndexes(def.table());
        Map<String, ColumnInfo> columns = debugService.tableColumns(def.table());
        return indexExplainer.explain(def, existing, columns);
    }

    private void addTriggerNote(ExplainerResult r, StatementType type) {
        boolean dml = type == StatementType.INSERT || type == StatementType.REPLACE
                || type == StatementType.UPDATE || type == StatementType.DELETE;
        if (!dml || r.targetTable == null) {
            return;
        }
        List<String> triggers = debugService.triggersOn(r.targetTable);
        if (!triggers.isEmpty()) {
            r.notes.add(0, "Table '" + r.targetTable + "' has trigger(s) that will fire for this statement: "
                    + String.join(", ", triggers) + ". Each runs once per affected row and adds to the cost.");
        }
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "WARNING" -> 0;
            case "NOTICE" -> 1;
            default -> 2;
        };
    }
}
