package com.mysqldebugger.service;

import com.mysqldebugger.model.ExecutionStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionFlowCteWindowTest {

    private final ExecutionFlowService service = new ExecutionFlowService();

    private static List<String> ops(List<ExecutionStep> steps) {
        return steps.stream().map(ExecutionStep::getOperation).toList();
    }

    @Test
    void cteStepsComeFirstAndMergeVersusMaterializeIsExplained() {
        List<ExecutionStep> steps = service.analyze(
                "WITH simple AS (SELECT id FROM t WHERE a = 1), "
                        + "agg AS (SELECT id, COUNT(*) c FROM t GROUP BY id) "
                        + "SELECT * FROM simple s JOIN agg a ON a.id = s.id");

        assertEquals(List.of("CTE", "CTE", "FROM", "JOIN", "SELECT"), ops(steps));
        assertTrue(steps.get(0).getDetails().contains("No temporary table"));
        assertTrue(steps.get(1).getDetails().contains("Materialized"));
    }

    @Test
    void windowStepSitsBetweenHavingAndSelect() {
        List<ExecutionStep> steps = service.analyze(
                "SELECT dept, SUM(x), RANK() OVER (ORDER BY SUM(x) DESC) FROM t GROUP BY dept HAVING SUM(x) > 1");

        assertEquals(List.of("FROM", "GROUP BY", "HAVING", "WINDOW", "SELECT"), ops(steps));
        assertTrue(steps.get(3).getExpression().contains("RANK() OVER (ORDER BY SUM(x) DESC)"));
    }

    @Test
    void recursiveCteIsDescribedAsIterative() {
        List<ExecutionStep> steps = service.analyze(
                "WITH RECURSIVE n AS (SELECT 1 AS v UNION ALL SELECT v + 1 FROM n WHERE v < 5) SELECT * FROM n");
        assertTrue(steps.get(0).getDescription().contains("recursive"));
        assertTrue(steps.get(0).getDetails().contains("cte_max_recursion_depth"));
    }

    @Test
    void unionIsExplainedAfterTheFirstSelect() {
        List<ExecutionStep> steps = service.analyze("SELECT a FROM t1 WHERE x = 1 UNION SELECT a FROM t2");
        assertEquals(List.of("FROM", "WHERE", "SELECT", "UNION"), ops(steps));
        assertTrue(steps.get(3).getDetails().contains("duplicate"));
    }

    @Test
    void windowParserReadsPartitionOrderAndFrame() {
        var uses = WindowFunctionParser.find(
                "SUM(a) OVER (PARTITION BY g ORDER BY d ROWS BETWEEN 1 PRECEDING AND CURRENT ROW), LAG(b) OVER w");
        assertEquals(2, uses.size());
        assertEquals("g", uses.get(0).partitionBy());
        assertEquals("d", uses.get(0).orderBy());
        assertTrue(uses.get(0).frame().startsWith("ROWS BETWEEN"));
        assertEquals("w", uses.get(1).namedWindow());
        assertEquals("value", uses.get(1).category());
    }
}
