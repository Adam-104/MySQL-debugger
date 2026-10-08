package com.mysqldebugger.service;

import com.mysqldebugger.model.ExecutionStep;
import com.mysqldebugger.model.ExplainResult;
import com.mysqldebugger.model.JsonExecutionPlan;
import com.mysqldebugger.model.PerformanceIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PerformanceAnalyzerTest {

    private final PerformanceAnalyzer analyzer = new PerformanceAnalyzer();
    private final ExecutionFlowService flowService = new ExecutionFlowService();

    private static ExplainResult row(String table, String type, long rows, String extra) {
        ExplainResult r = new ExplainResult();
        r.setTable(table);
        r.setType(type);
        r.setRows(rows);
        r.setExtra(extra);
        return r;
    }

    private static PerformanceIssue byTitle(List<PerformanceIssue> issues, String title) {
        return issues.stream().filter(i -> title.equals(i.getTitle())).findFirst().orElse(null);
    }

    @Test
    void largeFullScanIsWarningAndLinkedToWhere() {
        String q = "SELECT id FROM orders o WHERE o.status = 'PAID' ORDER BY o.created_at LIMIT 10";
        List<ExecutionStep> flow = flowService.analyze(q);

        List<PerformanceIssue> issues = analyzer.analyze(
                q, List.of(row("o", "ALL", 20000, "Using where; Using filesort")), flow, null);

        PerformanceIssue scan = byTitle(issues, "Full Table Scan");
        assertNotNull(scan);
        assertEquals("WARNING", scan.getSeverity());
        assertEquals("WHERE", scan.getRelatedOperation());
        assertEquals("o.status = 'PAID'", scan.getRelatedExpression());

        PerformanceIssue sort = byTitle(issues, "Filesort Detected");
        assertNotNull(sort);
        assertEquals("o.created_at", sort.getRelatedExpression()); // LIMIT must not leak in
    }

    @Test
    void smallFullScanIsOnlyNotice() {
        String q = "SELECT * FROM t";
        List<PerformanceIssue> issues = analyzer.analyze(
                q, List.of(row("t", "ALL", 5, null)), flowService.analyze(q), null);
        assertEquals("NOTICE", byTitle(issues, "Full Table Scan").getSeverity());
    }

    @Test
    void flagsSelectStarAndLeadingWildcard() {
        String q = "SELECT * FROM customers WHERE email LIKE '%@example.com'";
        List<PerformanceIssue> issues = analyzer.analyze(
                q, List.of(row("customers", "ref", 1, null)), flowService.analyze(q), null);

        assertNotNull(byTitle(issues, "SELECT * Used"));
        assertNotNull(byTitle(issues, "Leading Wildcard in LIKE"));
    }

    @Test
    void countStarIsNotSelectStar() {
        String q = "SELECT COUNT(*) FROM customers";
        List<PerformanceIssue> issues = analyzer.analyze(
                q, List.of(row("customers", "index", 1000, "Using index")), flowService.analyze(q), null);
        assertNull(byTitle(issues, "SELECT * Used"));
    }

    @Test
    void jsonPlanAddsFilesortAndTemporaryTableWhenExplainRowsMissThem() {
        String q = "SELECT city, COUNT(*) FROM customers GROUP BY city ORDER BY city";
        JsonExecutionPlan plan = new JsonExecutionPlan();
        plan.setOrderingUsingFilesort(true);
        plan.setGroupingUsingTemporaryTable(true);

        List<PerformanceIssue> issues = analyzer.analyze(q, List.of(), flowService.analyze(q), plan);

        assertNotNull(byTitle(issues, "Filesort Detected"));
        assertNotNull(byTitle(issues, "Temporary Table Used"));
    }
}
