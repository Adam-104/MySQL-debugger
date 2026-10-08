package com.mysqldebugger.model;

import java.util.ArrayList;
import java.util.List;

/** Internal result produced by the per-statement explainers. */
public class ExplainerResult {

    public final List<ExecutionStep> flow = new ArrayList<>();
    public final List<PerformanceIssue> issues = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    /** One-line description of what the statement does. */
    public String summary;

    /** SQL that can safely be sent to EXPLAIN (null if MySQL cannot explain it). */
    public String explainSql;

    /** Statement whose clauses the performance analyzer should read (e.g. a view's body). */
    public String analysisSql;

    /** Main table the statement works on (used to look up triggers / indexes). */
    public String targetTable;

    public ExecutionStep add(String operation, String description, String expression, String details) {
        ExecutionStep step = new ExecutionStep(flow.size() + 1, operation, description, expression, details);
        flow.add(step);
        return step;
    }

    public static PerformanceIssue issue(String severity, String title, String description,
                                         String reason, String suggestion) {
        PerformanceIssue i = new PerformanceIssue();
        i.setSeverity(severity);
        i.setTitle(title);
        i.setDescription(description);
        i.setReason(reason);
        i.setSuggestion(suggestion);
        return i;
    }
}
