package com.mysqldebugger.service;

import com.mysqldebugger.model.ExecutionStep;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains the logical order in which MySQL evaluates a SELECT, including CTEs,
 * window functions and UNION. Clause boundaries are located on a "masked" copy of
 * the query so keywords inside literals, function calls or subqueries are never
 * mistaken for top-level clauses.
 */
@Service
public class ExecutionFlowService {

    private static Pattern kw(String regex) {
        return Pattern.compile("\\b" + regex + "\\b", Pattern.CASE_INSENSITIVE);
    }

    private static final Pattern SELECT = kw("SELECT");
    private static final Pattern FROM = kw("FROM");
    private static final Pattern JOIN = kw(
            "(?:(?:NATURAL\\s+)?(?:INNER|LEFT|RIGHT|FULL|CROSS)\\s+(?:OUTER\\s+)?|NATURAL\\s+)?JOIN");
    private static final Pattern WHERE = kw("WHERE");
    private static final Pattern GROUP_BY = kw("GROUP\\s+BY");
    private static final Pattern HAVING = kw("HAVING");
    private static final Pattern ORDER_BY = kw("ORDER\\s+BY");
    private static final Pattern LIMIT = kw("LIMIT");
    private static final Pattern UNION = kw("UNION(?:\\s+(?:ALL|DISTINCT))?");
    private static final Pattern DISTINCT_PREFIX =
            Pattern.compile("^(DISTINCT|DISTINCTROW)\\b\\s*", Pattern.CASE_INSENSITIVE);

    private record Span(int start, int end) {
        boolean found() {
            return start >= 0;
        }
    }

    /** A step before numbering. */
    record Raw(String operation, String description, String expression, String details) {
    }

    public List<ExecutionStep> analyze(String query) {

        List<ExecutionStep> steps = new ArrayList<>();

        if (query == null || query.isBlank()) {
            return steps;
        }

        String q = SqlText.collapse(query);
        if (q.endsWith(";")) {
            q = q.substring(0, q.length() - 1).trim();
        }

        List<Raw> raw = new ArrayList<>();

        if (CteParser.startsWithWith(q)) {
            CteParser.Parsed parsed = CteParser.parse(q);
            raw.addAll(describeCtes(parsed));
            raw.addAll(selectBlock(parsed.mainStatement()));
        } else {
            raw.addAll(selectBlock(q));
        }

        int n = 1;
        for (Raw r : raw) {
            steps.add(new ExecutionStep(n++, r.operation(), r.description(), r.expression(), r.details()));
        }
        return steps;
    }

    // ------------------------------------------------------------------
    // CTEs
    // ------------------------------------------------------------------

    static List<Raw> describeCtes(CteParser.Parsed parsed) {

        List<Raw> out = new ArrayList<>();
        List<CteParser.Cte> ctes = parsed.ctes();

        for (int i = 0; i < ctes.size(); i++) {

            CteParser.Cte cte = ctes.get(i);
            Pattern ref = Pattern.compile("\\b" + Pattern.quote(cte.name()) + "\\b", Pattern.CASE_INSENSITIVE);

            boolean selfReference = ref.matcher(cte.body()).find();
            boolean recursive = parsed.recursive() && selfReference;

            int refs = countMatches(ref, parsed.mainStatement());
            for (int j = i + 1; j < ctes.size(); j++) {
                refs += countMatches(ref, ctes.get(j).body());
            }

            List<String> blockers = SqlFeatures.mergeBlockers(cte.body());

            String description;
            String details;

            if (recursive) {
                description = "MySQL builds the recursive CTE '" + cte.name() + "' iteratively: it runs the "
                        + "anchor (non-recursive) part once, then repeatedly runs the recursive part on the rows "
                        + "produced by the previous iteration until an iteration adds no new rows.";
                details = "Results are accumulated in an internal temporary table. Iteration stops with an error "
                        + "after cte_max_recursion_depth (default 1000) iterations, so the recursive part needs a "
                        + "termination condition.";
            } else if (!blockers.isEmpty() || refs > 1) {
                description = "MySQL evaluates the CTE '" + cte.name() + "' and stores its result in an internal "
                        + "temporary table (materialization) before the main query uses it.";
                String why = !blockers.isEmpty()
                        ? "it contains " + String.join(", ", blockers)
                        : "it is referenced " + refs + " times, so computing it once and reusing the result is cheaper";
                details = "Materialized because " + why + ". The temporary table has no indexes of your base tables, "
                        + "so filters in the main query are applied after the CTE has been fully computed.";
            } else {
                description = "MySQL treats the CTE '" + cte.name() + "' like a derived table: because it is "
                        + "simple and used once it is merged into the main query.";
                details = "No temporary table is created; the optimizer plans the CTE body together with the "
                        + "main query, so base-table indexes can still be used.";
            }

            out.add(new Raw("CTE", description,
                    cte.name() + (cte.columns() != null ? " (" + cte.columns() + ")" : "")
                            + " AS (" + SqlText.shorten(SqlText.collapse(cte.body()), 400) + ")",
                    details));
        }
        return out;
    }

    private static int countMatches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }

    // ------------------------------------------------------------------
    // SELECT block (with UNION handling)
    // ------------------------------------------------------------------

    private List<Raw> selectBlock(String q) {

        String masked = SqlText.mask(q);
        Span union = find(UNION, masked);

        if (!union.found()) {
            return clauses(q, masked);
        }

        String first = q.substring(0, union.start()).trim();
        List<Raw> out = new ArrayList<>(clauses(first, SqlText.mask(first)));
        boolean all = q.substring(union.start(), union.end()).toUpperCase().contains("ALL");

        out.add(new Raw("UNION",
                "MySQL runs each SELECT of the UNION separately and then combines the results. "
                        + "The steps above describe the first SELECT only.",
                SqlText.shorten(q.substring(union.start()).trim(), 400),
                all ? "UNION ALL appends the result sets without checking for duplicates."
                        : "Plain UNION also removes duplicate rows, using an internal temporary table with a "
                        + "unique key. Use UNION ALL if duplicates are impossible or acceptable."));
        return out;
    }

    private List<Raw> clauses(String q, String masked) {

        Span select = find(SELECT, masked);
        Span from = find(FROM, masked);
        Span join = find(JOIN, masked);
        Span where = find(WHERE, masked);
        Span groupBy = find(GROUP_BY, masked);
        Span having = find(HAVING, masked);
        Span orderBy = find(ORDER_BY, masked);
        Span limit = find(LIMIT, masked);

        List<Raw> out = new ArrayList<>();

        if (from.found()) {
            out.add(new Raw("FROM",
                    "MySQL identifies the source table(s) specified in the FROM clause.",
                    text(q, from, join, where, groupBy, having, orderBy, limit),
                    "Rows are retrieved from the specified table or tables."));
        }

        if (join.found()) {
            out.add(new Raw("JOIN",
                    "MySQL combines rows from the joined tables using the JOIN condition.",
                    q.substring(join.start(), end(q, join.end(), where, groupBy, having, orderBy, limit)).trim(),
                    "Rows from related tables are matched according to the join condition."));
        }

        if (where.found()) {
            out.add(new Raw("WHERE",
                    "MySQL filters rows based on the WHERE condition.",
                    text(q, where, groupBy, having, orderBy, limit),
                    "Only rows satisfying the WHERE condition continue to the next stage."));
        }

        if (groupBy.found()) {
            out.add(new Raw("GROUP BY",
                    "MySQL groups the remaining rows according to the GROUP BY expression.",
                    text(q, groupBy, having, orderBy, limit),
                    "Rows with the same grouping values are placed into the same group."));
        }

        if (having.found()) {
            out.add(new Raw("HAVING",
                    "MySQL filters the groups using the HAVING condition.",
                    text(q, having, orderBy, limit),
                    "Only groups satisfying the HAVING condition remain."));
        }

        String selectText = null;
        boolean hasDistinct = false;

        if (select.found()) {
            selectText = text(q, select, from, where, groupBy, having, orderBy, limit);
            Matcher distinct = DISTINCT_PREFIX.matcher(selectText);
            hasDistinct = distinct.find();
            if (hasDistinct) {
                selectText = selectText.substring(distinct.end()).trim();
            }
        }

        // Window functions are evaluated after GROUP BY / HAVING, before DISTINCT / ORDER BY.
        if (selectText != null) {
            List<WindowFunctionParser.WindowUse> windows = WindowFunctionParser.find(selectText);
            if (!windows.isEmpty()) {
                StringBuilder expr = new StringBuilder();
                for (WindowFunctionParser.WindowUse w : windows) {
                    if (expr.length() > 0) expr.append("\n");
                    expr.append(w.display());
                }
                out.add(new Raw("WINDOW",
                        "MySQL evaluates window functions on the rows that survived WHERE / GROUP BY / HAVING. "
                                + "Rows are split into partitions (PARTITION BY), sorted inside each partition "
                                + "(ORDER BY) and the function is computed over each row's frame. Unlike GROUP BY, "
                                + "no rows are collapsed.",
                        expr.toString(),
                        "Each distinct PARTITION BY / ORDER BY combination needs its own sort pass unless an index "
                        + "already provides that order."));
            }
        }

        if (select.found()) {
            out.add(new Raw("SELECT",
                    "MySQL determines the columns and expressions that will appear in the result.",
                    selectText,
                    "The requested columns, aliases, functions, and calculated expressions are evaluated."));

            if (hasDistinct) {
                out.add(new Raw("DISTINCT",
                        "MySQL removes duplicate rows from the result.",
                        "DISTINCT",
                        "Only unique result rows are retained."));
            }
        }

        if (orderBy.found()) {
            out.add(new Raw("ORDER BY",
                    "MySQL sorts the resulting rows according to the ORDER BY expression.",
                    text(q, orderBy, limit),
                    "The result rows are arranged in the requested ascending or descending order."));
        }

        if (limit.found()) {
            out.add(new Raw("LIMIT",
                    "MySQL restricts the number of rows returned to the client.",
                    text(q, limit),
                    "Only the specified number of rows are returned."));
        }

        return out;
    }

    // ------------------------------------------------------------------

    private static Span find(Pattern pattern, String masked) {
        Matcher m = pattern.matcher(masked);
        return m.find() ? new Span(m.start(), m.end()) : new Span(-1, -1);
    }

    private static String text(String q, Span clause, Span... following) {
        return q.substring(clause.end(), end(q, clause.end(), following)).trim();
    }

    private static int end(String q, int from, Span... following) {
        int end = q.length();
        for (Span s : following) {
            if (s.found() && s.start() >= from && s.start() < end) {
                end = s.start();
            }
        }
        return end;
    }
}
