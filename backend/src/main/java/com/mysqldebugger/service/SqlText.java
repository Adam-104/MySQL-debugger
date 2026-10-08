package com.mysqldebugger.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text helpers shared by the SQL parsers. */
final class SqlText {

    private SqlText() {
    }

    /**
     * Same-length copy of the SQL where string literals / quoted identifiers and
     * everything inside parentheses are replaced by spaces, so keyword searches
     * only see top-level SQL.
     */
    static String mask(String q) {

        StringBuilder out = new StringBuilder(q.length());
        int depth = 0;
        int i = 0;

        while (i < q.length()) {

            char c = q.charAt(i);

            if (c == '\'' || c == '"' || c == '`') {
                int start = i;
                i = skipQuoted(q, i);
                out.append(" ".repeat(i - start));
                continue;
            }

            if (c == '(') {
                out.append(depth == 0 ? c : ' ');
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                out.append(depth == 0 ? c : ' ');
            } else {
                out.append(depth > 0 ? ' ' : c);
            }
            i++;
        }

        return out.toString();
    }

    /** Returns the index just past the quoted section that starts at {@code start}. */
    static int skipQuoted(String q, int start) {
        char quote = q.charAt(start);
        int i = start + 1;
        while (i < q.length()) {
            char d = q.charAt(i);
            if (d == '\\' && quote != '`') {
                i += 2;
                continue;
            }
            if (d == quote) {
                if (i + 1 < q.length() && q.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return q.length();
    }

    /** Index of the ')' matching the '(' at {@code open}, or -1. Quotes are skipped. */
    static int matching(String q, int open) {
        int depth = 0;
        int i = open;
        while (i < q.length()) {
            char c = q.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(q, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    /** Splits on commas that are not inside parentheses or quotes. */
    static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(text, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(last, i).trim());
                last = i + 1;
            }
            i++;
        }
        String tail = text.substring(last).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    static String firstWord(String sql) {
        Matcher m = Pattern.compile("^[\\s(]*([A-Za-z_]+)").matcher(sql);
        return m.find() ? m.group(1).toUpperCase() : "";
    }

    static String stripQuotes(String identifier) {
        if (identifier == null) {
            return null;
        }
        String s = identifier.trim();
        if (s.length() >= 2 && s.startsWith("`") && s.endsWith("`")) {
            s = s.substring(1, s.length() - 1);
        }
        // schema.table -> table
        int dot = s.lastIndexOf('.');
        if (dot != -1) {
            s = s.substring(dot + 1).replace("`", "");
        }
        return s;
    }

    static String collapse(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    static String shorten(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).trim() + "…";
    }
}
