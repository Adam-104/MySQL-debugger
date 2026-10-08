package com.mysqldebugger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Detects SQL features that influence how MySQL can execute a query block. */
final class SqlFeatures {

    private static final Pattern AGGREGATE = Pattern.compile(
            "\\b(SUM|COUNT|AVG|MIN|MAX|GROUP_CONCAT|JSON_ARRAYAGG|JSON_OBJECTAGG|STD|STDDEV|VARIANCE)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OVER = Pattern.compile("\\bOVER\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTINCT = Pattern.compile("\\bDISTINCT(ROW)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GROUP_BY = Pattern.compile("\\bGROUP\\s+BY\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAVING = Pattern.compile("\\bHAVING\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNION = Pattern.compile("\\bUNION\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_SUBQUERY = Pattern.compile("\\(\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

    private SqlFeatures() {
    }

    /**
     * Constructs that stop MySQL from merging a view / CTE / derived table into
     * the outer query (it must be materialized into a temporary table instead).
     */
    static List<String> mergeBlockers(String body) {

        List<String> blockers = new ArrayList<>();
        if (body == null) {
            return blockers;
        }

        String top = SqlText.mask(body);

        if (AGGREGATE.matcher(top.replaceAll("\\s+", " ")).find()
                || AGGREGATE.matcher(withoutNestedSelects(body)).find()) {
            blockers.add("aggregate function (SUM, COUNT, ...)");
        }
        if (DISTINCT.matcher(top).find()) blockers.add("DISTINCT");
        if (GROUP_BY.matcher(top).find()) blockers.add("GROUP BY");
        if (HAVING.matcher(top).find()) blockers.add("HAVING");
        if (LIMIT.matcher(top).find()) blockers.add("LIMIT");
        if (UNION.matcher(top).find()) blockers.add("UNION");
        if (OVER.matcher(body).find()) blockers.add("window function (OVER)");
        if (SELECT_SUBQUERY.matcher(selectList(body)).find()) blockers.add("subquery in the select list");

        return blockers.stream().distinct().toList();
    }

    private static String withoutNestedSelects(String body) {
        return body;
    }

    static String selectList(String body) {
        String top = SqlText.mask(body);
        java.util.regex.Matcher from = Pattern.compile("\\bFROM\\b", Pattern.CASE_INSENSITIVE).matcher(top);
        return from.find() ? body.substring(0, from.start()) : body;
    }
}
