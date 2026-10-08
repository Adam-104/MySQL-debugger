package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import com.mysqldebugger.model.ExecutionStep;
import com.mysqldebugger.model.ExplainerResult;
import com.mysqldebugger.model.StatementType;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Execution flow for INSERT / REPLACE / UPDATE / DELETE. */
@Service
public class DmlExplainer {

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern INSERT_TARGET = Pattern.compile(
            "^\\s*(?:INSERT|REPLACE)\\s+(?:(?:LOW_PRIORITY|DELAYED|HIGH_PRIORITY|IGNORE)\\s+)*"
                    + "(?:INTO\\s+)?`?([\\w.]+)`?", F);
    private static final Pattern SET_KW = Pattern.compile("\\bSET\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_KW = Pattern.compile("\\bFROM\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHERE_KW = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_KW = Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_KW = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ON_DUP = Pattern.compile("\\bON\\s+DUPLICATE\\s+KEY\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JOIN_KW = Pattern.compile("\\bJOIN\\b", Pattern.CASE_INSENSITIVE);

    private final ExecutionFlowService flowService;

    public DmlExplainer(ExecutionFlowService flowService) {
        this.flowService = flowService;
    }

    public ExplainerResult explain(String sql, StatementType type) {

        ExplainerResult r = new ExplainerResult();
        r.explainSql = sql;

        String main = sql;
        if (CteParser.startsWithWith(sql)) {
            CteParser.Parsed parsed = CteParser.parse(sql);
            main = parsed.mainStatement();
            for (ExecutionFlowService.Raw c : ExecutionFlowService.describeCtes(parsed)) {
                r.add(c.operation(), c.description(), c.expression(), c.details());
            }
        }
        r.analysisSql = main;

        switch (type) {
            case INSERT, REPLACE -> insert(r, main, type);
            case UPDATE -> update(r, main);
            case DELETE -> delete(r, main);
            default -> throw new InvalidQueryException("Not a data-modification statement.");
        }

        r.notes.add("Every row change is first written to the InnoDB redo/undo logs, then to the table and each "
                + "of its indexes. The more indexes a table has, the more work each INSERT, UPDATE or DELETE does.");
        r.notes.add("Nothing is permanent until COMMIT. Rows touched by the statement stay locked until then, "
                + "so long-running writes block other sessions.");
        return r;
    }

    // ------------------------------------------------------------------ INSERT / REPLACE

    private void insert(ExplainerResult r, String sql, StatementType type) {

        Matcher m = INSERT_TARGET.matcher(sql);
        if (!m.find()) {
            throw new InvalidQueryException("Could not read the target table of the " + type + " statement.");
        }
        String table = SqlText.stripQuotes(m.group(1));
        r.targetTable = table;

        String rest = sql.substring(m.end()).trim();
        String columns = null;
        if (rest.startsWith("(")) {
            int close = SqlText.matching(rest, 0);
            if (close != -1) {
                String inside = rest.substring(1, close).trim();
                if (!SqlText.firstWord(inside).equals("SELECT")) {
                    columns = inside;
                    rest = rest.substring(close + 1).trim();
                }
            }
        }

        String masked = SqlText.mask(rest);
        Matcher dup = ON_DUP.matcher(masked);
        String onDup = null;
        String source = rest;
        if (dup.find()) {
            source = rest.substring(0, dup.start()).trim();
            onDup = rest.substring(dup.end()).trim();
        }

        String word = SqlText.firstWord(source);
        boolean isSelect = word.equals("SELECT") || source.startsWith("(") || word.equals("WITH");
        int rowCount = 0;
        if (word.equals("VALUES") || word.equals("VALUE")) {
            String tuples = source.substring(source.toUpperCase().indexOf(word) + word.length()).trim();
            rowCount = SqlText.splitTopLevel(tuples).size();
        }

        r.summary = type + " adds " + (isSelect ? "the rows produced by a SELECT"
                : rowCount > 0 ? rowCount + " row(s)" : "a row")
                + " to table '" + table + "'"
                + (type == StatementType.REPLACE ? ", replacing rows that collide on a unique key." : ".");

        r.add("TARGET", "MySQL resolves the table that receives the rows.", table,
                columns != null ? "Columns listed: " + columns
                        : "No column list given: values are matched to the table's columns by position.");

        if (isSelect) {
            r.add("SOURCE", "MySQL first executes the SELECT that produces the rows to insert.",
                    SqlText.shorten(SqlText.collapse(source), 400),
                    "The SELECT is planned like any query (see the steps below), and its rows are inserted as they are produced.");
            for (ExecutionStep s : flowService.analyze(source.startsWith("(") && source.endsWith(")")
                    ? source.substring(1, source.length() - 1) : source)) {
                r.add(s.getOperation(), s.getDescription(), s.getExpression(), s.getDetails());
            }
        } else {
            r.add("SOURCE", "MySQL evaluates the VALUES expressions for each row.",
                    SqlText.shorten(SqlText.collapse(source), 400),
                    rowCount > 1 ? "A multi-row INSERT is one statement: parsing, locking and the network round trip "
                            + "are paid once for all " + rowCount + " rows."
                            : "Each single-row INSERT pays the full statement overhead.");
        }

        r.add("BEFORE INSERT TRIGGERS",
                "For each row MySQL runs any BEFORE INSERT triggers, which may change or reject the new row (NEW.*).",
                "per row", "Runs before constraints are checked.");
        r.add("CONSTRAINTS",
                "MySQL fills defaults / AUTO_INCREMENT values and checks NOT NULL, CHECK, UNIQUE and FOREIGN KEY constraints.",
                "per row", type == StatementType.REPLACE
                        ? "On a PRIMARY/UNIQUE collision REPLACE deletes the old row and inserts the new one "
                        + "(DELETE and INSERT triggers both fire)."
                        : "A violated constraint aborts the statement (or is skipped with INSERT IGNORE).");
        r.add("WRITE ROW",
                "The row is written to the clustered (primary key) index and then to every secondary index.",
                "table '" + table + "'", "Each secondary index adds another B-tree insertion.");
        if (onDup != null) {
            r.add("ON DUPLICATE KEY UPDATE",
                    "If the row collides with a PRIMARY/UNIQUE key MySQL updates the existing row instead of inserting.",
                    onDup, "Fires BEFORE INSERT first, then BEFORE/AFTER UPDATE triggers when the update path is taken.");
        }
        r.add("AFTER INSERT TRIGGERS", "After the row is stored, AFTER INSERT triggers run for it.", "per row",
                "Typical use: audit logging or maintaining summary tables.");
        r.add("COMMIT", "At COMMIT the change becomes visible to other transactions and locks are released.", "", "");

        // issues
        if (columns == null && !isSelect) {
            r.issues.add(ExplainerResult.issue("INFO", "INSERT Without Column List",
                    "The statement relies on the table's column order.",
                    "If a column is later added or reordered, the same statement silently inserts values into the "
                            + "wrong columns or starts failing.",
                    "List the columns explicitly: INSERT INTO " + table + " (col1, col2) VALUES (...)."));
        }
        if (rowCount > 1000) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Very Large Multi-Row INSERT",
                    "The statement inserts " + rowCount + " rows at once.",
                    "A huge statement can exceed max_allowed_packet, builds a large undo log and holds locks for "
                            + "its whole duration.",
                    "Insert in batches of roughly 500–1000 rows per statement."));
        }
        if (rowCount == 1) {
            r.issues.add(ExplainerResult.issue("INFO", "Single-Row INSERT",
                    "One row per statement.",
                    "When loading many rows, each statement pays parsing, network and commit overhead.",
                    "Batch rows into one multi-row INSERT or wrap them in a single transaction."));
        }
    }

    // ------------------------------------------------------------------ UPDATE

    private void update(ExplainerResult r, String sql) {

        String q = SqlText.collapse(sql);
        String masked = SqlText.mask(q);

        Matcher set = SET_KW.matcher(masked);
        if (!set.find()) {
            throw new InvalidQueryException("UPDATE statement has no SET clause.");
        }

        String head = q.substring(0, set.start()).trim();
        Matcher kw = Pattern.compile("^UPDATE\\s+(?:(?:LOW_PRIORITY|IGNORE)\\s+)*", Pattern.CASE_INSENSITIVE).matcher(head);
        String targets = kw.find() ? head.substring(kw.end()).trim() : head;

        int whereAt = indexOf(WHERE_KW, masked, set.end());
        int orderAt = indexOf(ORDER_KW, masked, set.end());
        int limitAt = indexOf(LIMIT_KW, masked, set.end());

        String assignments = q.substring(set.end(), min(q.length(), whereAt, orderAt, limitAt)).trim();
        String where = clause(q, whereAt, 5, orderAt, limitAt);
        String order = clause(q, orderAt, 8, limitAt);
        String limit = clause(q, limitAt, 5);

        String firstTable = firstTable(targets);
        r.targetTable = firstTable;
        boolean multiTable = JOIN_KW.matcher(SqlText.mask(targets)).find() || SqlText.splitTopLevel(targets).size() > 1;

        r.summary = "UPDATE changes " + (where == null ? "every row" : "the rows matching the WHERE condition")
                + " of '" + firstTable + "'.";

        r.add("TARGET", multiTable
                        ? "MySQL joins the listed tables first, then updates the matching rows of the tables named in SET."
                        : "MySQL resolves the table whose rows will be changed.",
                targets, multiTable ? "Multi-table UPDATE: ORDER BY and LIMIT are not allowed." : "");

        if (where != null) {
            r.add("WHERE", "MySQL finds the rows to change, using an index if one is usable, otherwise scanning the table.",
                    where, "InnoDB locks every row it examines (not only the ones that match) until COMMIT.");
        } else {
            r.add("ALL ROWS", "There is no WHERE clause, so every row in the table is selected.", "", "");
        }
        if (order != null) {
            r.add("ORDER BY", "Rows are processed in this order (matters together with LIMIT).", order, "");
        }
        if (limit != null) {
            r.add("LIMIT", "MySQL stops after changing this many rows.", limit, "");
        }

        r.add("SET", "For each selected row MySQL evaluates the assignments from left to right using the current row values.",
                assignments, "In MySQL a later assignment sees the result of earlier ones in the same SET list.");
        r.add("BEFORE UPDATE TRIGGERS", "BEFORE UPDATE triggers run per row and may modify NEW.* or reject the change.",
                "per row", "");
        r.add("WRITE ROW", "The old version goes to the undo log, the row is updated in place, and only indexes "
                        + "containing changed columns are modified.",
                "table '" + firstTable + "'", "Updating an indexed column is more expensive than updating a non-indexed one.");
        r.add("AFTER UPDATE TRIGGERS", "AFTER UPDATE triggers run per row (OLD.* and NEW.* are both available).", "per row", "");
        r.add("COMMIT", "At COMMIT the new row versions become visible and locks are released.", "", "");

        writeChecks(r, "UPDATE", where, order, limit);
        r.issues.addAll(SelectExplainer.sqlLevelIssues(q));
    }

    // ------------------------------------------------------------------ DELETE

    private void delete(ExplainerResult r, String sql) {

        String q = SqlText.collapse(sql);
        String masked = SqlText.mask(q);

        Matcher from = FROM_KW.matcher(masked);
        if (!from.find()) {
            throw new InvalidQueryException("DELETE statement has no FROM clause.");
        }

        int whereAt = indexOf(WHERE_KW, masked, from.end());
        int orderAt = indexOf(ORDER_KW, masked, from.end());
        int limitAt = indexOf(LIMIT_KW, masked, from.end());

        String targets = q.substring(from.end(), min(q.length(), whereAt, orderAt, limitAt)).trim();
        String where = clause(q, whereAt, 5, orderAt, limitAt);
        String order = clause(q, orderAt, 8, limitAt);
        String limit = clause(q, limitAt, 5);

        String firstTable = firstTable(targets);
        r.targetTable = firstTable;

        r.summary = "DELETE removes " + (where == null ? "every row" : "the rows matching the WHERE condition")
                + " from '" + firstTable + "'.";

        r.add("TARGET", "MySQL resolves the table rows will be removed from.", targets, "");
        if (where != null) {
            r.add("WHERE", "MySQL finds the rows to delete, using an index if possible, otherwise scanning the table.",
                    where, "InnoDB locks every row it examines until COMMIT.");
        } else {
            r.add("ALL ROWS", "There is no WHERE clause, so every row is selected for deletion.", "", "");
        }
        if (order != null) r.add("ORDER BY", "Rows are deleted in this order (matters together with LIMIT).", order, "");
        if (limit != null) r.add("LIMIT", "MySQL stops after deleting this many rows.", limit, "");

        r.add("BEFORE DELETE TRIGGERS", "BEFORE DELETE triggers run per row and may reject the deletion.", "per row", "");
        r.add("FOREIGN KEYS", "MySQL checks foreign keys that reference these rows: it rejects the delete (RESTRICT) "
                        + "or cascades it to child rows (ON DELETE CASCADE / SET NULL).",
                "per row", "Cascaded changes made by foreign-key actions do not fire triggers in MySQL.");
        r.add("DELETE ROW", "Rows are delete-marked in the clustered index and every secondary index; a background "
                        + "purge removes them physically later.",
                "table '" + firstTable + "'", "Space is not returned to the file system immediately.");
        r.add("AFTER DELETE TRIGGERS", "AFTER DELETE triggers run per row (OLD.* is available).", "per row", "");
        r.add("COMMIT", "At COMMIT the deletion becomes visible and locks are released.", "", "");

        writeChecks(r, "DELETE", where, order, limit);
        r.issues.addAll(SelectExplainer.sqlLevelIssues(q));
    }

    // ------------------------------------------------------------------ shared

    private void writeChecks(ExplainerResult r, String kind, String where, String order, String limit) {

        if (where == null && limit == null) {
            r.issues.add(ExplainerResult.issue("WARNING", kind + " Without WHERE",
                    "The statement affects every row of the table.",
                    "It touches and locks all rows, produces a huge undo log, can stall replicas and cannot be "
                            + "undone after COMMIT.",
                    kind.equals("DELETE")
                            ? "Add a WHERE clause, delete in batches with LIMIT, or use TRUNCATE TABLE to empty a table."
                            : "Add a WHERE clause, or update in batches (WHERE id BETWEEN ... or LIMIT)."));
        }
        if (limit != null && order == null) {
            r.issues.add(ExplainerResult.issue("NOTICE", kind + " With LIMIT but No ORDER BY",
                    "Which rows are affected is not defined.",
                    "Without ORDER BY MySQL picks rows in whatever order it finds them, so repeated runs (and "
                            + "replicas) can change different rows. MySQL flags this as unsafe for replication.",
                    "Add ORDER BY on a unique column such as the primary key."));
        }
        if (order != null && limit == null) {
            r.issues.add(ExplainerResult.issue("INFO", "ORDER BY Without LIMIT",
                    kind + " sorts rows although every selected row is changed.",
                    "Sorting costs time and the final result is the same without it.",
                    "Remove ORDER BY unless it is combined with LIMIT."));
        }
    }

    private static int indexOf(Pattern p, String text, int from) {
        Matcher m = p.matcher(text);
        return m.find(from) ? m.start() : -1;
    }

    private static int min(int len, int... positions) {
        int best = len;
        for (int p : positions) {
            if (p >= 0 && p < best) best = p;
        }
        return best;
    }

    /** Text of a clause starting at {@code start} (after a keyword of length kwLen) up to the next clause. */
    private static String clause(String q, int start, int kwLen, int... next) {
        if (start < 0) {
            return null;
        }
        int end = q.length();
        for (int n : next) {
            if (n > start && n < end) end = n;
        }
        // keyword length is approximate for "ORDER BY" (8) and "WHERE"/"LIMIT" (5)
        return q.substring(Math.min(start + kwLen, end), end).trim();
    }

    private static String firstTable(String targets) {
        String first = SqlText.splitTopLevel(targets).isEmpty() ? targets : SqlText.splitTopLevel(targets).get(0);
        Matcher m = Pattern.compile("^`?([\\w.]+)`?").matcher(first.trim());
        return m.find() ? SqlText.stripQuotes(m.group(1)) : targets;
    }
}
