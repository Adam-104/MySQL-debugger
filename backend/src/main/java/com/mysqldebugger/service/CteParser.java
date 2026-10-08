package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits "WITH [RECURSIVE] a AS (...), b AS (...) <main statement>". */
final class CteParser {

    record Cte(String name, String columns, String body) {
    }

    record Parsed(boolean recursive, List<Cte> ctes, String mainStatement) {
    }

    private static final Pattern WITH_START =
            Pattern.compile("^\\s*WITH\\s+(RECURSIVE\\s+)?", Pattern.CASE_INSENSITIVE);

    private CteParser() {
    }

    static boolean startsWithWith(String sql) {
        return WITH_START.matcher(sql).find();
    }

    static Parsed parse(String sql) {

        Matcher start = WITH_START.matcher(sql);
        if (!start.find()) {
            throw new InvalidQueryException("Not a WITH query.");
        }

        boolean recursive = start.group(1) != null;
        int pos = start.end();
        List<Cte> ctes = new ArrayList<>();

        while (true) {

            pos = skipSpaces(sql, pos);

            // name
            int nameStart = pos;
            if (pos < sql.length() && sql.charAt(pos) == '`') {
                pos = SqlText.skipQuoted(sql, pos);
            } else {
                while (pos < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(pos)) || sql.charAt(pos) == '_')) {
                    pos++;
                }
            }
            if (pos == nameStart) {
                throw new InvalidQueryException("Could not read the CTE name in the WITH clause.");
            }
            String name = SqlText.stripQuotes(sql.substring(nameStart, pos));

            // optional column list
            pos = skipSpaces(sql, pos);
            String columns = null;
            if (pos < sql.length() && sql.charAt(pos) == '(') {
                int end = SqlText.matching(sql, pos);
                if (end == -1) {
                    throw new InvalidQueryException("Unbalanced parentheses in CTE column list.");
                }
                columns = sql.substring(pos + 1, end).trim();
                pos = skipSpaces(sql, end + 1);
            }

            // AS
            if (!sql.regionMatches(true, pos, "AS", 0, 2)) {
                throw new InvalidQueryException("Expected AS after CTE name '" + name + "'.");
            }
            pos = skipSpaces(sql, pos + 2);

            // ( body )
            if (pos >= sql.length() || sql.charAt(pos) != '(') {
                throw new InvalidQueryException("Expected '(' after AS in CTE '" + name + "'.");
            }
            int end = SqlText.matching(sql, pos);
            if (end == -1) {
                throw new InvalidQueryException("Unbalanced parentheses in CTE '" + name + "'.");
            }
            ctes.add(new Cte(name, columns, sql.substring(pos + 1, end).trim()));
            pos = skipSpaces(sql, end + 1);

            if (pos < sql.length() && sql.charAt(pos) == ',') {
                pos++;
                continue;
            }
            break;
        }

        String main = sql.substring(pos).trim();
        if (main.isEmpty()) {
            throw new InvalidQueryException("The WITH clause must be followed by a main statement.");
        }

        return new Parsed(recursive, ctes, main);
    }

    private static int skipSpaces(String s, int pos) {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
            pos++;
        }
        return pos;
    }
}
