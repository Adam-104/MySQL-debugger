package com.mysqldebugger.service;

import com.mysqldebugger.model.ExecutionStep;
import com.mysqldebugger.model.ExplainerResult;
import com.mysqldebugger.model.PerformanceIssue;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Execution flow + SQL-level advice for SELECT and WITH ... SELECT. */
@Service
public class SelectExplainer {

    private final ExecutionFlowService flowService;

    public SelectExplainer(ExecutionFlowService flowService) {
        this.flowService = flowService;
    }

    public ExplainerResult explain(String sql) {

        ExplainerResult r = new ExplainerResult();
        r.flow.addAll(flowService.analyze(sql));
        r.explainSql = sql;
        r.analysisSql = sql;

        String main = sql;
        int cteCount = 0;
        boolean recursive = false;

        if (CteParser.startsWithWith(sql)) {
            CteParser.Parsed parsed = CteParser.parse(sql);
            main = parsed.mainStatement();
            cteCount = parsed.ctes().size();
            recursive = parsed.recursive();
        }

        List<WindowFunctionParser.WindowUse> windows = WindowFunctionParser.find(main);

        StringBuilder summary = new StringBuilder("Reads data with a SELECT");
        if (cteCount > 0) summary.append(" built from ").append(cteCount).append(" CTE(s)");
        if (!windows.isEmpty()) summary.append(" using ").append(windows.size()).append(" window function(s)");
        summary.append(".");
        r.summary = summary.toString();

        r.notes.add("MySQL does not execute a query in the order it is written. The logical order is "
                + "FROM → JOIN → WHERE → GROUP BY → HAVING → window functions → SELECT → DISTINCT → ORDER BY → LIMIT. "
                + "That is why a column alias defined in SELECT cannot be used in WHERE.");
        r.notes.add("The optimizer is free to choose a different physical order (join order, index choice) as long "
                + "as the result is the same. The EXPLAIN table shows the plan it actually picked.");

        if (!windows.isEmpty()) {
            r.notes.add("Window functions run after grouping, so they can see aggregate results, but they cannot be "
                    + "used in WHERE or HAVING. Filter on a window result by wrapping the query in a subquery or CTE.");
        }
        if (cteCount > 0) {
            r.notes.add("A CTE is a named temporary result for one statement. MySQL either merges it into the outer "
                    + "query or materializes it into a temporary table; the flow above says which one applies.");
        }

        addWindowIssues(r, windows);
        if (recursive) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Recursive CTE",
                    "The query uses WITH RECURSIVE.",
                    "A recursive CTE repeats until no new rows appear. If the recursive part never stops producing "
                            + "rows (for example a cycle in hierarchical data) MySQL runs until it hits "
                            + "cte_max_recursion_depth (1000 by default) and fails.",
                    "Make sure the recursive part has a termination condition (such as a depth counter or "
                            + "WHERE depth < N) and that the join column used for recursion is indexed."));
        }

        addSqlLevelIssues(r, sql, main);
        return r;
    }

    // ------------------------------------------------------------------

    private void addWindowIssues(ExplainerResult r, List<WindowFunctionParser.WindowUse> windows) {

        if (windows.isEmpty()) {
            return;
        }

        Set<String> sortKeys = new HashSet<>();
        for (WindowFunctionParser.WindowUse w : windows) {
            sortKeys.add(w.sortKey());
        }

        if (sortKeys.size() > 1) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Multiple Window Sorts",
                    "The query uses " + sortKeys.size() + " different window definitions.",
                    "Each distinct PARTITION BY / ORDER BY combination forces MySQL to sort the rows again. "
                            + "Several sorts over a large result multiply CPU time and may spill to disk.",
                    "Reuse the same window definition where possible (WINDOW w AS (...)) and try to make "
                            + "an index match the PARTITION BY + ORDER BY columns."));
        }

        for (WindowFunctionParser.WindowUse w : windows) {

            if ("ranking".equals(w.category()) && w.orderBy() == null && w.namedWindow() == null) {
                r.issues.add(ExplainerResult.issue("WARNING", "Ranking Function Without ORDER BY",
                        w.function() + " is used without an ORDER BY inside OVER().",
                        "Ranking functions number rows by their order. Without ORDER BY the order inside a "
                                + "partition is arbitrary, so the numbers can change between runs.",
                        "Add ORDER BY to the OVER clause, for example OVER (PARTITION BY x ORDER BY y)."));
            }

            if (w.function().toUpperCase().startsWith("LAST_VALUE") && w.frame() == null
                    && w.namedWindow() == null) {
                r.issues.add(ExplainerResult.issue("INFO", "LAST_VALUE With Default Frame",
                        w.function() + " uses the default window frame.",
                        "With an ORDER BY the default frame ends at the CURRENT ROW, so LAST_VALUE simply returns "
                                + "the current row's value instead of the last value of the partition.",
                        "Add ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING to the OVER clause."));
            }
        }
    }

    // ------------------------------------------------------------------
    // SQL-level checks shared with DML / view explainers
    // ------------------------------------------------------------------

    private static final Pattern DEEP_OFFSET = Pattern.compile(
            "\\bLIMIT\\s+(\\d+)\\s*,\\s*\\d+|\\bOFFSET\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_BY_RAND = Pattern.compile(
            "\\bORDER\\s+BY\\s+RAND\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_SARGABLE = Pattern.compile(
            "\\b(DATE|YEAR|MONTH|DAY|LOWER|UPPER|TRIM|SUBSTRING|SUBSTR|LEFT|RIGHT|CAST|CONVERT|ABS|ROUND|IFNULL|COALESCE)"
                    + "\\s*\\(\\s*((?:\\w+\\.)?\\w+)\\s*(?:,[^()]*)?\\)\\s*(=|<>|!=|<=|>=|<|>|\\bLIKE\\b|\\bIN\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBQUERY_IN = Pattern.compile(
            "\\b(NOT\\s+)?IN\\s*\\(\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

    private void addSqlLevelIssues(ExplainerResult r, String fullSql, String main) {

        r.issues.addAll(sqlLevelIssues(main));

        String top = SqlText.mask(main);
        if (Pattern.compile("\\bUNION\\b(?!\\s+ALL)", Pattern.CASE_INSENSITIVE).matcher(top).find()) {
            r.issues.add(ExplainerResult.issue("INFO", "UNION Removes Duplicates",
                    "The query uses UNION without ALL.",
                    "To remove duplicates MySQL builds a temporary table with a unique key over the whole "
                            + "combined result, which costs memory/disk and time.",
                    "Use UNION ALL when the parts cannot overlap or duplicates are fine."));
        }

        if (SUBQUERY_IN.matcher(main).find()) {
            r.issues.add(ExplainerResult.issue("INFO", "Subquery in IN (...)",
                    "The WHERE clause filters with IN (SELECT ...).",
                    "MySQL 8 usually rewrites this into a semijoin, but NOT IN (SELECT ...) cannot be, and a "
                            + "NULL in the subquery makes NOT IN return no rows at all.",
                    "Consider EXISTS / NOT EXISTS or a JOIN, and make sure the subquery's column is indexed."));
        }

        String selectList = SqlFeatures.selectList(main);
        if (Pattern.compile("\\(\\s*SELECT\\b", Pattern.CASE_INSENSITIVE).matcher(selectList).find()) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Subquery in SELECT List",
                    "A scalar subquery is evaluated for the output columns.",
                    "A correlated subquery in the select list can run once per result row, turning one query into "
                            + "thousands.",
                    "Rewrite it as a JOIN (with GROUP BY if needed) or a window function."));
        }
    }

    /** Checks that only need the SQL text; also used by the DML and view explainers. */
    static List<PerformanceIssue> sqlLevelIssues(String sql) {

        List<PerformanceIssue> issues = new java.util.ArrayList<>();

        if (ORDER_BY_RAND.matcher(sql).find()) {
            issues.add(ExplainerResult.issue("WARNING", "ORDER BY RAND()",
                    "The query sorts by RAND().",
                    "MySQL has to generate a random value for every row, store all rows in a temporary table and "
                            + "sort them, no matter how small the LIMIT is.",
                    "Pick random ids in the application, or select a random id range using an indexed column."));
        }

        Matcher pg = DEEP_OFFSET.matcher(sql);
        if (pg.find()) {
            String g = pg.group(1) != null ? pg.group(1) : pg.group(2);
            try {
                if (Long.parseLong(g) >= 10_000) {
                    issues.add(ExplainerResult.issue("NOTICE", "Deep Pagination",
                            "The query skips " + g + " rows with OFFSET.",
                            "MySQL still reads and discards every skipped row, so page 1000 costs far more than "
                                    + "page 1.",
                            "Use keyset pagination: WHERE id > :last_seen_id ORDER BY id LIMIT n."));
                }
            } catch (NumberFormatException ignored) {
                // huge number, ignore
            }
        }

        // function wrapped around a column inside the WHERE clause
        Matcher where = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE).matcher(SqlText.mask(sql));
        if (where.find()) {
            Matcher ns = NON_SARGABLE.matcher(sql.substring(where.end()));
            if (ns.find()) {
                issues.add(ExplainerResult.issue("WARNING",
                        "Function on Column Prevents Index Use",
                        "The WHERE clause applies " + ns.group(1).toUpperCase() + "() to column "
                                + ns.group(2) + " before comparing it.",
                        "An index is sorted by the raw column value. Once the column is wrapped in a function MySQL "
                                + "cannot seek in the index and must evaluate the function for every row.",
                        "Rewrite the condition so the column stands alone, e.g. "
                                + "created_at >= '2025-01-01' AND created_at < '2026-01-01' instead of YEAR(created_at) = 2025."));
            }
        }

        return issues;
    }
}
