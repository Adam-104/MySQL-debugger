package com.mysqldebugger.model;

public class PerformanceIssue {

    private String severity;
    private String title;

    private String table;
    private Long estimatedRows;

    private String relatedOperation;
    private String relatedExpression;

    private String description;
    private String suggestion;
    private String reason;

    public PerformanceIssue() {}

    public PerformanceIssue(
            String severity,
            String title,
            String table,
            Long estimatedRows,
            String relatedOperation,
            String relatedExpression,
            String description,
            String suggestion) {
        this.severity = severity;
        this.title = title;
        this.table = table;
        this.estimatedRows = estimatedRows;
        this.relatedOperation = relatedOperation;
        this.relatedExpression = relatedExpression;
        this.description = description;
        this.suggestion = suggestion;
    }

    public String getSeverity() {
        return severity;
    }
    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }

    public String getTable() {
        return table;
    }
    public void setTable(String table) {
        this.table = table;
    }

    public Long getEstimatedRows() {
        return estimatedRows;
    }
    public void setEstimatedRows(Long estimatedRows) {
        this.estimatedRows = estimatedRows;
    }

    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    public String getRelatedOperation() {
        return relatedOperation;
    }
    public void setRelatedOperation(String relatedOperation) {
        this.relatedOperation = relatedOperation;
    }

    public String getRelatedExpression() {
        return relatedExpression;
    }
    public void setRelatedExpression(String relatedExpression) {
        this.relatedExpression = relatedExpression;
    }

    public String getSuggestion() {
        return suggestion;
    }
    public void setSuggestion(String suggestion) {
        this.suggestion = suggestion;
    }

    /** Why this matters: what it costs and why the fix is worth making. */
    public String getReason() {
        return reason;
    }
    public void setReason(String reason) {
        this.reason = reason;
    }
}
