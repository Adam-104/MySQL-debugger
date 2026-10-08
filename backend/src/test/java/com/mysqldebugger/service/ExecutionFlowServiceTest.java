package com.mysqldebugger.service;

import com.mysqldebugger.model.ExecutionStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionFlowServiceTest {

    private final ExecutionFlowService service = new ExecutionFlowService();

    private static ExecutionStep step(List<ExecutionStep> steps, String op) {
        return steps.stream().filter(s -> s.getOperation().equals(op)).findFirst().orElse(null);
    }

    @Test
    void ordersStepsInLogicalExecutionOrder() {
        List<ExecutionStep> steps = service.analyze(
                "SELECT DISTINCT c.city FROM customers c JOIN orders o ON o.customer_id = c.id "
                        + "WHERE o.status = 'PAID' GROUP BY c.city HAVING COUNT(*) > 1 "
                        + "ORDER BY c.city LIMIT 5");

        assertEquals(
                List.of("FROM", "JOIN", "WHERE", "GROUP BY", "HAVING",
                        "SELECT", "DISTINCT", "ORDER BY", "LIMIT"),
                steps.stream().map(ExecutionStep::getOperation).toList());

        for (int i = 0; i < steps.size(); i++) {
            assertEquals(i + 1, steps.get(i).getStep());
        }
    }

    @Test
    void extractsClauseExpressions() {
        List<ExecutionStep> steps = service.analyze(
                "SELECT c.city, COUNT(*) FROM customers c LEFT JOIN orders o ON o.customer_id = c.id "
                        + "WHERE o.status = 'PAID' GROUP BY c.city ORDER BY c.city");

        assertEquals("customers c", step(steps, "FROM").getExpression());
        assertEquals("LEFT JOIN orders o ON o.customer_id = c.id", step(steps, "JOIN").getExpression());
        assertEquals("o.status = 'PAID'", step(steps, "WHERE").getExpression());
        assertEquals("c.city, COUNT(*)", step(steps, "SELECT").getExpression());
    }

    @Test
    void ignoresKeywordsInsideLiteralsAndSubqueries() {
        List<ExecutionStep> steps = service.analyze(
                "SELECT id FROM t WHERE note = 'ORDER BY x' AND id IN (SELECT id FROM u WHERE a = 1 LIMIT 3)");

        assertNull(step(steps, "ORDER BY"));
        assertNull(step(steps, "LIMIT"));
        assertEquals("note = 'ORDER BY x' AND id IN (SELECT id FROM u WHERE a = 1 LIMIT 3)",
                step(steps, "WHERE").getExpression());
    }

    @Test
    void columnNamesContainingKeywordsAreNotClauses() {
        List<ExecutionStep> steps = service.analyze("SELECT from_date, order_by_x FROM t");
        assertEquals("t", step(steps, "FROM").getExpression());
        assertNull(step(steps, "ORDER BY"));
    }
}
