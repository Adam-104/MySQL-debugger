package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import com.mysqldebugger.model.ColumnInfo;
import com.mysqldebugger.model.DebugResponse;
import com.mysqldebugger.model.ExecutionStep;
import com.mysqldebugger.model.ExplainResult;
import com.mysqldebugger.model.IndexInfo;
import com.mysqldebugger.model.JsonExecutionPlan;
import com.mysqldebugger.model.StatementType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests of the analysis pipeline with a fake database layer
 * (EXPLAIN output and INFORMATION_SCHEMA data are supplied by the test).
 */
class AnalysisServiceTest {

    private List<ExplainResult> plan;
    private final Map<String, List<IndexInfo>> indexes = new HashMap<>();
    private final Map<String, Map<String, ColumnInfo>> columns = new HashMap<>();
    private final Map<String, List<String>> triggers = new HashMap<>();
    private String lastExplainSql;
    private AnalysisService service;

    private class FakeDebugService extends DebugService {
        FakeDebugService() {
            super(null, null);
        }

        @Override
        public List<ExplainResult> debugQuery(String query) {
            lastExplainSql = query;
            return plan;
        }

        @Override
        public JsonExecutionPlan debugJsonQuery(String query) {
            JsonExecutionPlan p = new JsonExecutionPlan();
            p.setQueryCost(10.0);
            return p;
        }

        @Override
        public List<IndexInfo> existingIndexes(String table) {
            return indexes.getOrDefault(table, List.of());
        }

        @Override
        public Map<String, ColumnInfo> tableColumns(String table) {
            return columns.getOrDefault(table, Map.of());
        }

        @Override
        public List<String> triggersOn(String table) {
            return triggers.getOrDefault(table, List.of());
        }
    }

    @BeforeEach
    void setUp() {
        ExecutionFlowService flow = new ExecutionFlowService();
        SelectExplainer select = new SelectExplainer(flow);
        service = new AnalysisService(new QueryValidator(), new StatementClassifier(), select,
                new DmlExplainer(flow), new ViewExplainer(flow, select), new TriggerExplainer(),
                new IndexExplainer(), new IndexSuggester(), new PerformanceAnalyzer(), new FakeDebugService(), null);
        plan = new ArrayList<>();
    }

    private static ExplainResult row(String table, String type, long rows, String extra) {
        ExplainResult r = new ExplainResult();
        r.setTable(table);
        r.setType(type);
        r.setRows(rows);
        r.setExtra(extra);
        return r;
    }

    private static boolean hasIssue(DebugResponse r, String title) {
        return r.getPerformanceIssues().stream().anyMatch(i -> title.equals(i.getTitle()));
    }

    private static List<String> ops(DebugResponse r) {
        return r.getExecutionFlow().stream().map(ExecutionStep::getOperation).toList();
    }

    // ---------------------------------------------------------------- SELECT / CTE / window

    @Test
    void cteWithWindowFunctions() {
        plan.add(row("p", "ALL", 19876, "Using where; Using filesort"));
        DebugResponse r = service.analyze(
                "WITH paid AS (SELECT customer_id, amount, created_at FROM orders WHERE status = 'PAID'), "
                        + "big AS (SELECT customer_id, SUM(amount) AS total FROM paid GROUP BY customer_id) "
                        + "SELECT p.customer_id, ROW_NUMBER() OVER (PARTITION BY p.customer_id ORDER BY p.created_at DESC) rn, "
                        + "SUM(p.amount) OVER (ORDER BY p.created_at) running "
                        + "FROM paid p JOIN big b ON b.customer_id = p.customer_id WHERE b.total > 100 "
                        + "ORDER BY p.created_at LIMIT 10");

        assertEquals(StatementType.CTE_SELECT, r.getStatementType());
        assertEquals(List.of("CTE", "CTE"), ops(r).subList(0, 2));
        assertTrue(ops(r).indexOf("WINDOW") < ops(r).indexOf("SELECT"));
        assertTrue(hasIssue(r, "Multiple Window Sorts"));
        assertTrue(hasIssue(r, "Full Table Scan"));
        r.getPerformanceIssues().forEach(i -> assertNotNull(i.getReason(), i.getTitle() + " needs a reason"));
    }

    @Test
    void sqlLevelAdvice() {
        plan.add(row("orders", "ALL", 20000, null));
        DebugResponse r = service.analyze(
                "SELECT * FROM orders WHERE YEAR(created_at) = 2025 ORDER BY RAND() LIMIT 50000, 10");
        assertTrue(hasIssue(r, "Function on Column Prevents Index Use"));
        assertTrue(hasIssue(r, "ORDER BY RAND()"));
        assertTrue(hasIssue(r, "Deep Pagination"));
    }

    @Test
    void recursiveCteIsFlagged() {
        plan.add(row("e", "ref", 5, null));
        DebugResponse r = service.analyze(
                "WITH RECURSIVE t AS (SELECT id, 1 d FROM emp WHERE parent_id IS NULL "
                        + "UNION ALL SELECT e.id, t.d + 1 FROM emp e JOIN t ON e.parent_id = t.id) SELECT * FROM t");
        assertTrue(hasIssue(r, "Recursive CTE"));
    }

    // ---------------------------------------------------------------- index suggestions

    @Test
    void suggestsEqualityThenRangeIndex() {
        plan.add(row("o", "ALL", 19876, "Using where; Using filesort"));
        DebugResponse r = service.analyze(
                "SELECT o.id FROM orders o WHERE o.status = 'PAID' AND o.amount > 100 ORDER BY o.created_at LIMIT 20");

        assertEquals(1, r.getIndexSuggestions().size());
        assertEquals("orders", r.getIndexSuggestions().get(0).table());
        assertEquals(List.of("status", "amount"), r.getIndexSuggestions().get(0).columns());
        assertEquals("CREATE INDEX idx_orders_status_amount ON orders (status, amount);",
                r.getIndexSuggestions().get(0).ddl());
    }

    @Test
    void doesNotSuggestAnIndexThatAlreadyExists() {
        plan.add(row("o", "ALL", 19876, null));
        indexes.put("orders", List.of(new IndexInfo("idx_s", List.of("status", "amount", "created_at"), false)));
        DebugResponse r = service.analyze("SELECT o.id FROM orders o WHERE o.status = 'PAID' AND o.amount > 100");
        assertTrue(r.getIndexSuggestions().isEmpty());
    }

    @Test
    void neverSuggestsASelectAliasAsAnIndexColumn() {
        plan.add(row("orders", "ALL", 5000, "Using filesort"));
        DebugResponse r = service.analyze("SELECT id, total AS revenue FROM orders ORDER BY revenue DESC");
        assertTrue(r.getIndexSuggestions().isEmpty());
    }

    // ---------------------------------------------------------------- DML

    @Test
    void updateWithoutWhereIsWarnedAndExistingTriggersAreSurfaced() {
        plan.add(row("orders", "ALL", 20000, null));
        triggers.put("orders", List.of("trg_audit (AFTER UPDATE)"));

        DebugResponse r = service.analyze("UPDATE orders SET status = 'DONE'");

        assertEquals(StatementType.UPDATE, r.getStatementType());
        assertTrue(hasIssue(r, "UPDATE Without WHERE"));
        assertTrue(r.getNotes().get(0).contains("trg_audit"));
        assertTrue(lastExplainSql.startsWith("UPDATE"));
    }

    @Test
    void deleteFlowAndChecks() {
        plan.add(row("orders", "range", 10, null));
        DebugResponse r = service.analyze("DELETE FROM orders WHERE created_at < '2020-01-01' LIMIT 100");
        assertTrue(ops(r).containsAll(List.of("TARGET", "WHERE", "LIMIT", "FOREIGN KEYS", "DELETE ROW")));
        assertTrue(hasIssue(r, "DELETE With LIMIT but No ORDER BY"));
    }

    @Test
    void insertVariants() {
        plan.add(row("orders", null, 0, null));
        DebugResponse values = service.analyze(
                "INSERT INTO orders VALUES (1, 2, 3.5, 'PAID', NOW()), (2, 3, 4.5, 'PAID', NOW())");
        assertTrue(values.getSummary().contains("2 row(s)"));
        assertTrue(hasIssue(values, "INSERT Without Column List"));

        DebugResponse fromSelect = service.analyze(
                "INSERT INTO orders (customer_id, amount) SELECT id, 10 FROM customers WHERE city = 'Delhi' "
                        + "ON DUPLICATE KEY UPDATE amount = amount + 1");
        assertTrue(ops(fromSelect).containsAll(List.of("SOURCE", "WHERE", "ON DUPLICATE KEY UPDATE")));
    }

    @Test
    void withPrefixedDml() {
        plan.add(row("orders", "ALL", 10, null));
        DebugResponse r = service.analyze(
                "WITH x AS (SELECT id FROM customers WHERE city = 'Delhi') "
                        + "DELETE FROM orders WHERE customer_id IN (SELECT id FROM x)");
        assertEquals(StatementType.DELETE, r.getStatementType());
        assertEquals("CTE", ops(r).get(0));
    }

    // ---------------------------------------------------------------- views

    @Test
    void viewWithAggregateCannotMerge() {
        plan.add(row("orders", "ALL", 20000, "Using temporary"));
        DebugResponse r = service.analyze(
                "CREATE OR REPLACE ALGORITHM=MERGE VIEW v_revenue AS SELECT customer_id, SUM(amount) AS revenue "
                        + "FROM orders GROUP BY customer_id WITH CHECK OPTION");

        assertEquals(StatementType.CREATE_VIEW, r.getStatementType());
        assertTrue(hasIssue(r, "ALGORITHM=MERGE Cannot Be Honored"));
        assertTrue(ops(r).contains("WHEN QUERIED (TEMPTABLE)"));
        assertTrue(ops(r).contains("WITH CHECK OPTION"));
        assertTrue(lastExplainSql.startsWith("SELECT customer_id"), "only the view body is explained");
    }

    @Test
    void simpleViewMerges() {
        plan.add(row("orders", "ALL", 10, null));
        DebugResponse r = service.analyze("CREATE VIEW v_open AS SELECT id, status FROM orders WHERE status <> 'DONE'");
        assertTrue(ops(r).contains("WHEN QUERIED (MERGE)"));
    }

    // ---------------------------------------------------------------- triggers

    @Test
    void triggerAnalysisNeverTouchesTheDatabase() {
        DebugResponse r = service.analyze("""
                DELIMITER $$
                CREATE TRIGGER trg_orders_audit AFTER UPDATE ON orders FOR EACH ROW
                BEGIN
                  SET NEW.updated_at = NOW();
                  UPDATE orders SET amount = amount WHERE id = NEW.id;
                  INSERT INTO order_audit (order_id, old_status) VALUES (OLD.id, OLD.status);
                  SELECT COUNT(*) INTO @n FROM order_audit;
                END$$
                DELIMITER ;
                """);

        assertEquals(StatementType.CREATE_TRIGGER, r.getStatementType());
        assertTrue(r.getExecutionPlan().isEmpty());
        assertNull(lastExplainSql);
        assertTrue(hasIssue(r, "Changing NEW In An AFTER Trigger"));
        assertTrue(hasIssue(r, "Trigger Modifies Its Own Table"));
        assertTrue(hasIssue(r, "Extra Write Per Row"));
        assertTrue(hasIssue(r, "Query Inside Trigger"));
    }

    @Test
    void invalidNewOldReferences() {
        assertTrue(hasIssue(service.analyze(
                "CREATE TRIGGER t BEFORE DELETE ON orders FOR EACH ROW SET @x = NEW.id"), "NEW Used In DELETE Trigger"));
        assertTrue(hasIssue(service.analyze(
                "CREATE TRIGGER t BEFORE INSERT ON orders FOR EACH ROW SET @x = OLD.id"), "OLD Used In INSERT Trigger"));
    }

    // ---------------------------------------------------------------- indexes

    private void givenOrdersTable() {
        indexes.put("orders", List.of(
                new IndexInfo("PRIMARY", List.of("id"), true),
                new IndexInfo("idx_cust", List.of("customer_id"), false)));
        Map<String, ColumnInfo> cols = new LinkedHashMap<>();
        cols.put("id", new ColumnInfo("id", "int", null));
        cols.put("customer_id", new ColumnInfo("customer_id", "int", null));
        cols.put("status", new ColumnInfo("status", "tinyint", null));
        cols.put("notes", new ColumnInfo("notes", "text", 65535L));
        columns.put("orders", cols);
    }

    @Test
    void indexChecksAgainstExistingSchema() {
        givenOrdersTable();

        assertTrue(hasIssue(service.analyze("CREATE INDEX i ON orders (customer_id)"), "Duplicate Of Existing Index"));
        assertTrue(hasIssue(service.analyze("CREATE INDEX i ON orders (customer_id, status)"),
                "Makes 'idx_cust' Redundant"));
        assertTrue(hasIssue(service.analyze("CREATE INDEX i ON orders (status)"), "Low-Selectivity Column"));

        DebugResponse alter = service.analyze("ALTER TABLE orders ADD INDEX i (notes)");
        assertEquals(StatementType.CREATE_INDEX, alter.getStatementType());
        assertTrue(hasIssue(alter, "TEXT/BLOB Column Needs A Prefix Length"));

        DebugResponse unknown = service.analyze("CREATE UNIQUE INDEX i ON orders (nope)");
        assertTrue(hasIssue(unknown, "Unknown Column 'nope'"));
        assertTrue(ops(unknown).contains("UNIQUENESS CHECK"));
    }

    // ---------------------------------------------------------------- rejections

    @Test
    void rejectsUnsupportedAndUnsafeInput() {
        for (String bad : new String[]{"DROP TABLE orders", "CREATE TABLE t (id int)",
                "SELECT 1; DELETE FROM orders", "SELECT SLEEP(5)", ""}) {
            assertThrows(InvalidQueryException.class, () -> service.analyze(bad), bad);
        }
    }
}
