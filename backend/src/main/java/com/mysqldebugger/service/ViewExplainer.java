package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import com.mysqldebugger.model.ExecutionStep;
import com.mysqldebugger.model.ExplainerResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explains CREATE VIEW: how MySQL stores it and how it executes when queried. */
@Service
public class ViewExplainer {

    private static final Pattern HEADER = Pattern.compile(
            "^\\s*CREATE\\s+(OR\\s+REPLACE\\s+)?(?:ALGORITHM\\s*=\\s*(\\w+)\\s+)?"
                    + "(?:DEFINER\\s*=\\s*\\S+\\s+)?(?:SQL\\s+SECURITY\\s+(\\w+)\\s+)?"
                    + "VIEW\\s+`?([\\w.]+)`?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CHECK_OPTION = Pattern.compile(
            "\\s+WITH\\s+(?:(CASCADED|LOCAL)\\s+)?CHECK\\s+OPTION\\s*$", Pattern.CASE_INSENSITIVE);

    private final ExecutionFlowService flowService;
    private final SelectExplainer selectExplainer;

    public ViewExplainer(ExecutionFlowService flowService, SelectExplainer selectExplainer) {
        this.flowService = flowService;
        this.selectExplainer = selectExplainer;
    }

    public ExplainerResult explain(String sql) {

        Matcher h = HEADER.matcher(sql);
        if (!h.find()) {
            throw new InvalidQueryException("Could not parse CREATE VIEW statement.");
        }

        String algorithm = h.group(2) == null ? "UNDEFINED" : h.group(2).toUpperCase();
        String security = h.group(3) == null ? "DEFINER" : h.group(3).toUpperCase();
        String name = SqlText.stripQuotes(h.group(4));

        String rest = sql.substring(h.end()).trim();

        String columns = null;
        if (rest.startsWith("(")) {
            int close = SqlText.matching(rest, 0);
            if (close != -1) {
                columns = rest.substring(1, close).trim();
                rest = rest.substring(close + 1).trim();
            }
        }

        Matcher as = Pattern.compile("^AS\\b\\s*", Pattern.CASE_INSENSITIVE).matcher(rest);
        if (!as.find()) {
            throw new InvalidQueryException("Expected AS followed by a SELECT in CREATE VIEW.");
        }
        String body = rest.substring(as.end()).trim();

        String checkOption = null;
        Matcher co = CHECK_OPTION.matcher(body);
        if (co.find()) {
            checkOption = co.group(1) == null ? "CASCADED" : co.group(1).toUpperCase();
            body = body.substring(0, co.start()).trim();
        }

        if (body.startsWith("(") && body.endsWith(")") && SqlText.matching(body, 0) == body.length() - 1) {
            body = body.substring(1, body.length() - 1).trim();
        }

        String bodyWord = SqlText.firstWord(body);
        if (!bodyWord.equals("SELECT") && !bodyWord.equals("WITH")) {
            throw new InvalidQueryException("The view body must be a SELECT statement.");
        }

        List<String> blockers = SqlFeatures.mergeBlockers(body);
        boolean mergeable = blockers.isEmpty();
        // MERGE requested but impossible: MySQL silently falls back to TEMPTABLE
        String resolved = algorithm.equals("TEMPTABLE") ? "TEMPTABLE"
                : (mergeable ? "MERGE" : "TEMPTABLE");

        ExplainerResult r = new ExplainerResult();
        r.targetTable = name;
        r.analysisSql = body;
        r.explainSql = body;
        r.summary = "Defines the view '" + name + "', a stored SELECT that behaves like a virtual table.";

        r.add("STORE DEFINITION",
                "CREATE VIEW does not run the query or copy any data. MySQL only saves the SELECT text "
                        + "(and column names) in the data dictionary.",
                name + (columns != null ? " (" + columns + ")" : "") + " AS " + SqlText.shorten(SqlText.collapse(body), 300),
                "SQL SECURITY " + security + ": the view runs with the privileges of "
                        + (security.equals("DEFINER") ? "its creator." : "the user who queries it."));

        r.add("CHOOSE ALGORITHM",
                "Each time someone queries the view, MySQL decides between MERGE (rewrite the outer query and the "
                        + "view body into one query) and TEMPTABLE (run the view first into a temporary table).",
                "ALGORITHM = " + algorithm + "  →  " + resolved,
                mergeable
                        ? "This body has nothing that blocks merging, so MERGE is possible."
                        : "TEMPTABLE is needed because the body contains: " + String.join(", ", blockers) + ".");

        if (resolved.equals("MERGE")) {
            r.add("WHEN QUERIED (MERGE)",
                    "The outer query's WHERE / ORDER BY / LIMIT are combined with the view body into a single query "
                            + "on the base tables.",
                    "SELECT ... FROM " + name + " WHERE ...",
                    "Indexes on the base tables can be used for the outer filter, and the view stays updatable "
                            + "(when it maps to single rows of base tables).");
        } else {
            r.add("WHEN QUERIED (TEMPTABLE)",
                    "MySQL runs the whole view body first and stores the result in an internal temporary table; "
                            + "the outer query then reads from that table.",
                    "SELECT ... FROM " + name + " WHERE ...",
                    "Outer filters are applied after the view is fully computed, base-table indexes cannot help "
                            + "with them, and the view is not updatable.");
        }

        if (checkOption != null) {
            r.add("WITH CHECK OPTION",
                    "Rows inserted or updated through the view must still satisfy the view's WHERE condition, "
                            + "otherwise the change is rejected.",
                    checkOption + " CHECK OPTION", "");
        }

        // body flow, so the user sees how the underlying SELECT is executed
        for (ExecutionStep s : flowService.analyze(body)) {
            r.add(s.getOperation(), s.getDescription(), s.getExpression(), s.getDetails());
        }

        r.notes.add("A view is not a cache: its query runs again every time it is used, so a slow view stays slow.");
        r.notes.add("The columns of the view are fixed when it is created. Columns added to the base tables later "
                + "do not appear unless the view is recreated.");

        if (!mergeable && algorithm.equals("MERGE")) {
            r.issues.add(ExplainerResult.issue("WARNING", "ALGORITHM=MERGE Cannot Be Honored",
                    "The view requests MERGE but contains " + String.join(", ", blockers) + ".",
                    "MySQL silently falls back to TEMPTABLE (with a warning), so the performance you expect from "
                            + "MERGE is not delivered.",
                    "Remove the blocking construct or use ALGORITHM=UNDEFINED / TEMPTABLE deliberately."));
        } else if (!mergeable) {
            r.issues.add(ExplainerResult.issue("NOTICE", "View Is Materialized On Every Use",
                    "The view body contains " + String.join(", ", blockers) + ".",
                    "Every query on the view first builds a temporary table with the full view result, even if the "
                            + "outer query only wants a few rows.",
                    "Keep views lean, push selective filters into the view or query the base tables directly "
                            + "for large data."));
        }

        if (Pattern.compile("(^|,)\\s*(\\w+\\.)?\\*\\s*(,|$)").matcher(SqlFeatures.selectList(body).replaceFirst("(?i)^\\s*(WITH.*?)?SELECT\\s+(DISTINCT\\s+)?", "")).find()) {
            r.issues.add(ExplainerResult.issue("INFO", "SELECT * Inside a View",
                    "The view body selects every column.",
                    "MySQL expands * once, when the view is created. Columns added later are missing from the view "
                            + "and dropped columns make it fail.",
                    "List the columns explicitly."));
        }

        if (algorithm.equals("TEMPTABLE") && mergeable) {
            r.issues.add(ExplainerResult.issue("INFO", "ALGORITHM=TEMPTABLE Forced",
                    "The view could be merged but is forced to use a temporary table.",
                    "TEMPTABLE materializes the whole view on every use and stops outer filters from using "
                            + "base-table indexes. It also makes the view non-updatable.",
                    "Use ALGORITHM=UNDEFINED (the default) unless you specifically need a consistent snapshot."));
        }

        r.issues.addAll(SelectExplainer.sqlLevelIssues(body));

        if (Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE).matcher(SqlText.mask(body)).find()
                && !Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE).matcher(SqlText.mask(body)).find()) {
            r.issues.add(ExplainerResult.issue("INFO", "ORDER BY in a View",
                    "The view body has an ORDER BY without LIMIT.",
                    "If the outer query has its own ORDER BY, the view's ordering is ignored, so it adds no value "
                            + "and may add sorting cost.",
                    "Put ORDER BY in the query that uses the view."));
        }

        return r;
    }
}
