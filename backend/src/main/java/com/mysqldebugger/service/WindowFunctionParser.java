package com.mysqldebugger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds "func(...) OVER (...)" usages in a SELECT list. */
final class WindowFunctionParser {

    record WindowUse(String function, String partitionBy, String orderBy, String frame,
                     String namedWindow, String category) {

        String spec() {
            if (namedWindow != null) {
                return "window " + namedWindow;
            }
            StringBuilder sb = new StringBuilder();
            if (partitionBy != null) sb.append("PARTITION BY ").append(partitionBy);
            if (orderBy != null) sb.append(sb.length() > 0 ? " " : "").append("ORDER BY ").append(orderBy);
            if (frame != null) sb.append(sb.length() > 0 ? " " : "").append(frame);
            return sb.length() == 0 ? "(entire result set)" : sb.toString();
        }

        /** Key used to decide whether two windows need separate sort passes. */
        String sortKey() {
            return namedWindow != null
                    ? "named:" + namedWindow
                    : (partitionBy == null ? "" : partitionBy.toLowerCase().replaceAll("\\s+", ""))
                    + "|" + (orderBy == null ? "" : orderBy.toLowerCase().replaceAll("\\s+", ""));
        }

        String display() {
            return function + " OVER (" + (namedWindow != null ? namedWindow : spec()) + ")";
        }
    }

    private static final Pattern OVER =
            Pattern.compile("\\bOVER\\s*(\\(|[A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTITION = Pattern.compile(
            "PARTITION\\s+BY\\s+(.*?)(?=\\s+ORDER\\s+BY\\b|\\s+(?:ROWS|RANGE|GROUPS)\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ORDER = Pattern.compile(
            "ORDER\\s+BY\\s+(.*?)(?=\\s+(?:ROWS|RANGE|GROUPS)\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FRAME = Pattern.compile(
            "\\b((?:ROWS|RANGE|GROUPS)\\b.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<String> RANKING = Set.of(
            "ROW_NUMBER", "RANK", "DENSE_RANK", "NTILE", "PERCENT_RANK", "CUME_DIST");
    private static final Set<String> VALUE = Set.of(
            "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE", "NTH_VALUE");

    private WindowFunctionParser() {
    }

    static List<WindowUse> find(String sql) {

        List<WindowUse> uses = new ArrayList<>();
        if (sql == null) {
            return uses;
        }

        Matcher m = OVER.matcher(sql);
        while (m.find()) {

            String function = functionBefore(sql, m.start());
            if (function == null) {
                continue;
            }
            String name = function.substring(0, function.indexOf('(')).trim().toUpperCase();
            String category = RANKING.contains(name) ? "ranking"
                    : VALUE.contains(name) ? "value" : "aggregate";

            if (m.group(1).equals("(")) {
                int open = m.end() - 1;
                int close = SqlText.matching(sql, open);
                if (close == -1) {
                    continue;
                }
                String spec = SqlText.collapse(sql.substring(open + 1, close));
                uses.add(new WindowUse(function,
                        first(PARTITION, spec), first(ORDER, spec), first(FRAME, spec), null, category));
            } else {
                uses.add(new WindowUse(function, null, null, null, m.group(1), category));
            }
        }
        return uses;
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Walks backwards from OVER to read "name(args)". */
    private static String functionBefore(String sql, int overStart) {

        int i = overStart - 1;
        while (i >= 0 && Character.isWhitespace(sql.charAt(i))) {
            i--;
        }
        if (i < 0 || sql.charAt(i) != ')') {
            return null;
        }

        int close = i;
        int depth = 0;
        while (i >= 0) {
            char c = sql.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                depth--;
                if (depth == 0) break;
            }
            i--;
        }
        if (i < 0) {
            return null;
        }

        int open = i;
        int nameEnd = open;
        int nameStart = nameEnd;
        while (nameStart > 0 && (Character.isLetterOrDigit(sql.charAt(nameStart - 1))
                || sql.charAt(nameStart - 1) == '_')) {
            nameStart--;
        }
        if (nameStart == nameEnd) {
            return null;
        }
        return SqlText.collapse(sql.substring(nameStart, close + 1));
    }
}
