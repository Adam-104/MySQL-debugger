package com.mysqldebugger.model;

import java.util.ArrayList;
import java.util.List;

public class DebugResponse {

    private String query;
    private List<ExecutionStep> executionFlow;
    private List<ExplainResult> executionPlan;
    private JsonExecutionPlan jsonExecutionPlan;
    private List<PerformanceIssue> performanceIssues;
    private StatementType statementType;
    private String summary;
    private List<String> notes = new ArrayList<>();
    private List<IndexSuggestion> indexSuggestions = new ArrayList<>();

    public DebugResponse() {
    }

    public DebugResponse(
            String query,
            List<ExecutionStep> executionFlow,
            List<ExplainResult> executionPlan,
            JsonExecutionPlan jsonExecutionPlan,
            List<PerformanceIssue> performanceIssues) {

        this.query = query;
        this.executionFlow = executionFlow;
        this.executionPlan = executionPlan;
        this.jsonExecutionPlan = jsonExecutionPlan;
        this.performanceIssues = performanceIssues;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public List<ExecutionStep> getExecutionFlow() {
        return executionFlow;
    }

    public void setExecutionFlow(List<ExecutionStep> executionFlow) {
        this.executionFlow = executionFlow;
    }

    public List<ExplainResult> getExecutionPlan() {
        return executionPlan;
    }

    public void setExecutionPlan(List<ExplainResult> executionPlan) {
        this.executionPlan = executionPlan;
    }

    public JsonExecutionPlan getJsonExecutionPlan() {
        return jsonExecutionPlan;
    }

    public void setJsonExecutionPlan(JsonExecutionPlan jsonExecutionPlan) {
        this.jsonExecutionPlan = jsonExecutionPlan;
    }

    public List<PerformanceIssue> getPerformanceIssues() {
        return performanceIssues;
    }

    public void setPerformanceIssues(
            List<PerformanceIssue> performanceIssues) {

        this.performanceIssues = performanceIssues;
    }

    public StatementType getStatementType() { return statementType; }
    public void setStatementType(StatementType statementType) { this.statementType = statementType; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }

    public List<IndexSuggestion> getIndexSuggestions() { return indexSuggestions; }
    public void setIndexSuggestions(List<IndexSuggestion> indexSuggestions) { this.indexSuggestions = indexSuggestions; }
}
