package com.mysqldebugger.service;

import com.mysqldebugger.model.ExecutionStep;
import com.mysqldebugger.model.ExplainResult;
import com.mysqldebugger.model.JsonExecutionPlan;
import com.mysqldebugger.model.PerformanceIssue;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PerformanceAnalyzer {

    public List<PerformanceIssue> analyze(
            String query,
            List<ExplainResult> executionPlan,
            List<ExecutionStep> executionFlow,
            JsonExecutionPlan jsonExecutionPlan
    ) {

        List<PerformanceIssue> issues = new ArrayList<>();

        // ---------------------------------------------------------
        // 1. Analyze EXPLAIN execution plan
        // ---------------------------------------------------------

        if (executionPlan != null) {

            for (ExplainResult result : executionPlan) {

                String table = result.getTable();
                Long rows = result.getRows();
                String type = result.getType();
                String possibleKeys = result.getPossibleKeys();
                String key = result.getKey();
                String extra = result.getExtra();

                // -------------------------------------------------
                // FULL TABLE SCAN
                // -------------------------------------------------

                if ("ALL".equalsIgnoreCase(type)) {

                    PerformanceIssue issue = new PerformanceIssue();

                    issue.setSeverity(
                            rows != null && rows > 1000 ? "WARNING" : "NOTICE");
                    issue.setTitle("Full Table Scan");
                    issue.setReason("Without a usable index MySQL reads every row of the table (about " + (rows == null ? "all" : rows) + " here). The work grows linearly with table size, so a query that is fast today slows down as data grows, and the scan also pushes useful pages out of the buffer pool.");
                    issue.setTable(table);
                    issue.setEstimatedRows(rows);

                    String relatedOperation = findRelatedOperation(
                            table,
                            query,
                            executionFlow
                    );

                    issue.setRelatedOperation(relatedOperation);

                    String relatedExpression = findRelatedExpression(
                            relatedOperation,
                            executionFlow
                    );

                    issue.setRelatedExpression(relatedExpression);

                    issue.setDescription(
                            "MySQL is using a full table scan for table '"
                                    + table
                                    + "'. Estimated rows: "
                                    + rows
                                    + "."
                    );

                    if (rows != null && rows <= 10) {

                        issue.setSuggestion(
                                "The table is currently small, so the full scan "
                                        + "is unlikely to be a significant performance "
                                        + "problem. Monitor it as the table grows."
                        );

                    } else {

                        issue.setSuggestion(
                                "Consider adding an appropriate index to the "
                                        + "columns used for filtering or joining."
                        );
                    }

                    issues.add(issue);
                }

                // -------------------------------------------------
                // INDEX AVAILABLE BUT NOT USED
                // -------------------------------------------------

                if (possibleKeys != null
                        && !possibleKeys.isBlank()
                        && (key == null || key.isBlank())) {

                    PerformanceIssue issue = new PerformanceIssue();

                    issue.setSeverity("INFO");
                    issue.setTitle("Index Available but Not Selected");
                    issue.setReason("The optimizer estimated that using the index (random lookups back into the table) would cost more than reading the table sequentially, usually because the filter matches a large share of the rows or the statistics are stale.");
                    issue.setTable(table);
                    issue.setEstimatedRows(rows);

                    String relatedOperation = findRelatedOperation(
                            table,
                            query,
                            executionFlow
                    );

                    issue.setRelatedOperation(relatedOperation);

                    String relatedExpression = findRelatedExpression(
                            relatedOperation,
                            executionFlow
                    );

                    issue.setRelatedExpression(relatedExpression);

                    issue.setDescription(
                            "MySQL identified possible index(es): "
                                    + possibleKeys
                                    + ", but did not select an index."
                    );

                    issue.setSuggestion(
                            "The optimizer may have determined that another "
                                    + "access method is cheaper. Check table size, "
                                    + "index selectivity, and filtering conditions "
                                    + "before forcing an index."
                    );

                    issues.add(issue);
                }

                // -------------------------------------------------
                // FILESORT
                // -------------------------------------------------

                if (extra != null
                        && extra.toLowerCase().contains("filesort")) {

                    PerformanceIssue issue = new PerformanceIssue();

                    issue.setSeverity("NOTICE");
                    issue.setTitle("Filesort Detected");
                    issue.setReason("When no index provides the requested order, MySQL must collect all matching rows and sort them. This costs CPU and memory, and large sorts spill to temporary files on disk.");
                    issue.setTable(table);
                    issue.setEstimatedRows(rows);

                    issue.setRelatedOperation("ORDER BY");

                    String orderExpression =
                            findOrderByExpression(query);

                    issue.setRelatedExpression(orderExpression);

                    issue.setDescription(
                            "MySQL is using an additional sorting operation "
                                    + "for the ORDER BY clause."
                    );

                    issue.setSuggestion(
                            "Review the ORDER BY expression and determine whether "
                                    + "an appropriate index can reduce the sorting work."
                    );

                    issues.add(issue);
                }

                // -------------------------------------------------
                // TEMPORARY TABLE
                // -------------------------------------------------

                if (extra != null
                        && extra.toLowerCase().contains("temporary")) {

                    PerformanceIssue issue = new PerformanceIssue();

                    issue.setSeverity("NOTICE");
                    issue.setTitle("Temporary Table Used");
                    issue.setReason("MySQL had to build an intermediate table to evaluate this query. Temporary tables use memory first and are converted to slower on-disk tables when they grow large.");
                    issue.setTable(table);
                    issue.setEstimatedRows(rows);

                    issue.setRelatedOperation("GROUP BY");

                    String groupExpression =
                            findGroupByExpression(query);

                    issue.setRelatedExpression(groupExpression);

                    issue.setDescription(
                            "MySQL is using a temporary table while "
                                    + "processing the grouping operation."
                    );

                    issue.setSuggestion(
                            "Review GROUP BY, aggregation, DISTINCT, and "
                                    + "ORDER BY operations. A temporary table "
                                    + "is not necessarily a problem for small "
                                    + "result sets."
                    );

                    issues.add(issue);
                }

                // -------------------------------------------------
                // JOIN BUFFER
                // -------------------------------------------------

                if (extra != null
                        && extra.toLowerCase().contains("join buffer")) {

                    PerformanceIssue issue = new PerformanceIssue();

                    issue.setSeverity("INFO");
                    issue.setTitle("Join Buffer Used");
                    issue.setReason("With no index on the join column, MySQL compares rows in blocks held in the join buffer, which is roughly a nested loop over both tables: cost is rows(A) × rows(B) in the worst case.");
                    issue.setTable(table);
                    issue.setEstimatedRows(rows);

                    issue.setRelatedOperation("JOIN");

                    String joinExpression =
                            findJoinExpression(query);

                    issue.setRelatedExpression(joinExpression);

                    issue.setDescription(
                            "MySQL is using a join buffer while processing "
                                    + "this table."
                    );

                    issue.setSuggestion(
                            "Review the JOIN condition and indexes on the "
                                    + "columns used to join the tables."
                    );

                    issues.add(issue);
                }
            }
        }

        // ---------------------------------------------------------
        // 2. Analyze JSON execution plan
        // ---------------------------------------------------------

        if (jsonExecutionPlan != null) {

            if (jsonExecutionPlan.isOrderingUsingFilesort()) {

                boolean alreadyExists = issues.stream()
                        .anyMatch(issue ->
                                "Filesort Detected".equals(issue.getTitle()));

                if (!alreadyExists) {

                    PerformanceIssue issue = new PerformanceIssue();

                    issue.setSeverity("NOTICE");
                    issue.setTitle("Filesort Detected");
                    issue.setReason("When no index provides the requested order, MySQL must collect all matching rows and sort them. This costs CPU and memory, and large sorts spill to temporary files on disk.");
                    issue.setRelatedOperation("ORDER BY");
                    issue.setRelatedExpression(
                            findOrderByExpression(query)
                    );

                    issue.setDescription(
                            "The JSON execution plan shows that MySQL "
                                    + "uses filesort for the ordering operation."
                    );

                    issue.setSuggestion(
                            "Review the ORDER BY expression and consider "
                                    + "whether an appropriate index can "
                                    + "reduce sorting."
                    );

                    issues.add(issue);
                }
            }

            if (jsonExecutionPlan.isGroupingUsingTemporaryTable()) {

                boolean alreadyExists = issues.stream()
                        .anyMatch(issue ->
                                "Temporary Table Used".equals(issue.getTitle()));

                if (!alreadyExists) {

                    PerformanceIssue issue = new PerformanceIssue();

                    issue.setSeverity("NOTICE");
                    issue.setTitle("Temporary Table Used");
                    issue.setReason("MySQL had to build an intermediate table to evaluate this query. Temporary tables use memory first and are converted to slower on-disk tables when they grow large.");
                    issue.setRelatedOperation("GROUP BY");
                    issue.setRelatedExpression(
                            findGroupByExpression(query)
                    );

                    issue.setDescription(
                            "The JSON execution plan shows that MySQL "
                                    + "uses a temporary table during grouping."
                    );

                    issue.setSuggestion(
                            "Review GROUP BY, aggregation, DISTINCT, and "
                                    + "ORDER BY operations."
                    );

                    issues.add(issue);
                }
            }
        }

        // ---------------------------------------------------------
        // 3. Query-level checks
        // ---------------------------------------------------------

        addQueryLevelIssues(query, executionFlow, issues);

        return issues;
    }

    private static final java.util.regex.Pattern SELECT_STAR =
            java.util.regex.Pattern.compile("(^|,)\\s*(\\w+\\.)?\\*\\s*(,|$)");

    private static final java.util.regex.Pattern LEADING_WILDCARD =
            java.util.regex.Pattern.compile(
                    "\\bLIKE\\s+(?:'%|\"%)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private void addQueryLevelIssues(
            String query,
            List<ExecutionStep> executionFlow,
            List<PerformanceIssue> issues) {

        if (executionFlow != null) {
            for (ExecutionStep step : executionFlow) {
                if ("SELECT".equalsIgnoreCase(step.getOperation())
                        && step.getExpression() != null
                        && SELECT_STAR.matcher(step.getExpression()).find()) {

                    PerformanceIssue issue = new PerformanceIssue();
                    issue.setSeverity("INFO");
                    issue.setTitle("SELECT * Used");
                    issue.setReason("Selecting every column transfers data the application may never use, and it prevents covering indexes, where MySQL answers the query from the index alone without reading the table rows.");
                    issue.setRelatedOperation("SELECT");
                    issue.setRelatedExpression(step.getExpression());
                    issue.setDescription(
                            "The query selects every column, which can prevent "
                                    + "covering-index usage and transfers unneeded data.");
                    issue.setSuggestion(
                            "List only the columns you need.");
                    issues.add(issue);
                    break;
                }
            }
        }

        if (query != null && LEADING_WILDCARD.matcher(query).find()) {

            PerformanceIssue issue = new PerformanceIssue();
            issue.setSeverity("WARNING");
            issue.setTitle("Leading Wildcard in LIKE");
                    issue.setReason("A B-tree index is sorted from the first character. With a leading % the matching values can be anywhere in that order, so MySQL cannot seek and has to test every row.");
            issue.setRelatedOperation("WHERE");
            issue.setRelatedExpression(
                    findRelatedExpression("WHERE", executionFlow));
            issue.setDescription(
                    "A LIKE pattern that starts with '%' cannot use a B-tree index "
                            + "and forces MySQL to examine every row.");
            issue.setSuggestion(
                    "Avoid a leading wildcard, or consider a FULLTEXT index "
                            + "for text search.");
            issues.add(issue);
        }
    }

    // =============================================================
    // Find operation related to a table
    // =============================================================

    private String findRelatedOperation(
            String table,
            String query,
            List<ExecutionStep> executionFlow
    ) {

        if (query == null) {
            return "UNKNOWN";
        }

        // Prefer the clause that actually mentions this table / alias
        if (table != null && executionFlow != null && !table.startsWith("<")) {

            java.util.regex.Pattern mention = java.util.regex.Pattern.compile(
                    "\\b" + java.util.regex.Pattern.quote(table) + "\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

            for (String operation : new String[]{"WHERE", "JOIN", "GROUP BY", "ORDER BY"}) {
                for (ExecutionStep step : executionFlow) {
                    if (operation.equalsIgnoreCase(step.getOperation())
                            && step.getExpression() != null
                            && mention.matcher(step.getExpression()).find()) {
                        return operation;
                    }
                }
            }
        }

        String lowerQuery = query.toLowerCase();

        if (lowerQuery.contains(" where ")) {
            return "WHERE";
        }

        if (lowerQuery.contains(" join ")) {
            return "JOIN";
        }

        if (lowerQuery.contains(" group by ")) {
            return "GROUP BY";
        }

        return "SELECT";
    }

    // =============================================================
    // Find expression associated with operation
    // =============================================================

    private String findRelatedExpression(
            String operation,
            List<ExecutionStep> executionFlow
    ) {

        if (executionFlow == null) {
            return null;
        }

        for (ExecutionStep step : executionFlow) {

            if (step.getOperation() != null
                    && step.getOperation().equalsIgnoreCase(operation)) {

                return step.getExpression();
            }
        }

        return null;
    }

    // =============================================================
    // ORDER BY expression
    // =============================================================

    private String findOrderByExpression(String query) {

        if (query == null) {
            return null;
        }

        String lowerQuery = query.toLowerCase();

        int index = lowerQuery.indexOf("order by");

        if (index == -1) {
            return null;
        }

        int end = lowerQuery.indexOf(" limit ", index);

        return (end == -1
                ? query.substring(index + 8)
                : query.substring(index + 8, end)).trim();
    }

    // =============================================================
    // GROUP BY expression
    // =============================================================

    private String findGroupByExpression(String query) {

        if (query == null) {
            return null;
        }

        String lowerQuery = query.toLowerCase();

        int index = lowerQuery.indexOf("group by");

        if (index == -1) {
            return null;
        }

        int end = lowerQuery.indexOf("having", index);

        if (end == -1) {
            end = lowerQuery.indexOf("order by", index);
        }

        if (end == -1) {
            return query.substring(index + 8).trim();
        }

        return query.substring(index + 8, end).trim();
    }

    // =============================================================
    // JOIN expression
    // =============================================================

    private String findJoinExpression(String query) {

        if (query == null) {
            return null;
        }

        String lowerQuery = query.toLowerCase();

        int index = lowerQuery.indexOf(" join ");

        if (index == -1) {
            return null;
        }

        int end = lowerQuery.indexOf(" where ", index);

        if (end == -1) {
            end = lowerQuery.indexOf(" group by ", index);
        }

        if (end == -1) {
            end = lowerQuery.indexOf(" order by ", index);
        }

        if (end == -1) {
            return query.substring(index + 6).trim();
        }

        return query.substring(index + 6, end).trim();
    }
}