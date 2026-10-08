package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safety checks that run before anything reaches the database.
 *
 * The tool analyses many kinds of statements (SELECT, DML, CREATE VIEW / TRIGGER /
 * INDEX) but only ever sends EXPLAIN for statements MySQL can explain without
 * executing; CREATE statements are never run. Stacked statements, executable
 * comments and a few dangerous functions are still rejected.
 */
@Component
public class QueryValidator {

    private static final int MAX_LENGTH = 20_000;

    private static final Pattern TRIGGER_START = Pattern.compile(
            "^\\s*CREATE\\s+(?:DEFINER\\s*=\\s*\\S+\\s+)?TRIGGER\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DANGEROUS = Pattern.compile(
            "\\b(SLEEP|BENCHMARK|LOAD_FILE|OUTFILE|DUMPFILE)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DELIMITER_LINE = Pattern.compile(
            "(?im)^\\s*DELIMITER\\s+(\\S+)\\s*$");

    /**
     * Validates the SQL and returns a cleaned version: DELIMITER lines and comments
     * removed, trimmed, trailing ';' removed.
     */
    public String validate(String sql) {

        if (sql == null || sql.isBlank()) {
            throw new InvalidQueryException("Query must not be empty.");
        }

        if (sql.length() > MAX_LENGTH) {
            throw new InvalidQueryException(
                    "Query is too long (max " + MAX_LENGTH + " characters).");
        }

        // /*! ... */ comments are executed by MySQL, so they can hide statements.
        if (sql.contains("/*!")) {
            throw new InvalidQueryException(
                    "MySQL executable comments (/*! ... */) are not allowed.");
        }

        String text = removeDelimiterDirectives(sql);

        String cleaned = scan(text, false).trim();
        String sanitized = scan(text, true).trim();

        if (cleaned.isEmpty()) {
            throw new InvalidQueryException("Query must not be empty.");
        }

        boolean trigger = TRIGGER_START.matcher(sanitized).find();

        // A trigger body legitimately contains ';' (BEGIN ... END). It is only analysed.
        if (!trigger) {
            int semicolon = sanitized.indexOf(';');
            if (semicolon != -1 && semicolon != sanitized.length() - 1) {
                throw new InvalidQueryException("Only a single statement is allowed.");
            }

            var dangerous = DANGEROUS.matcher(sanitized);
            if (dangerous.find()) {
                throw new InvalidQueryException(
                        "'" + dangerous.group(1).toUpperCase() + "' is not allowed in analyzed queries.");
            }
        }

        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        return cleaned;
    }

    /** Handles scripts copied from the mysql client: DELIMITER $$ ... END$$ DELIMITER ; */
    private static String removeDelimiterDirectives(String sql) {

        Matcher m = DELIMITER_LINE.matcher(sql);
        String custom = null;
        while (m.find()) {
            if (!m.group(1).equals(";")) {
                custom = m.group(1);
            }
        }

        String result = DELIMITER_LINE.matcher(sql).replaceAll("");

        if (custom != null) {
            result = result.replace(custom, " ");
        }
        return result.trim();
    }

    /**
     * Removes comments. When {@code blankLiterals} is true, string literals and
     * quoted identifiers are also emptied so keyword checks only see real SQL.
     */
    static String scan(String sql, boolean blankLiterals) {

        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = sql.length();

        while (i < n) {

            char c = sql.charAt(i);

            if (c == '\'' || c == '"' || c == '`') {

                int end = SqlText.skipQuoted(sql, i);
                if (blankLiterals) {
                    out.append(c).append(c);
                } else {
                    out.append(sql, i, end);
                }
                i = end;

            } else if (c == '#'
                    || (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2))))) {

                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append(' ');

            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {

                int end = sql.indexOf("*/", i + 2);
                i = (end == -1) ? n : end + 2;
                out.append(' ');

            } else {
                out.append(c);
                i++;
            }
        }

        return out.toString();
    }
}
