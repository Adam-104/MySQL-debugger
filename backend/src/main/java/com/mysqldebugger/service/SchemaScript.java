package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validates the "paste your own schema" text and splits it into single statements. Pure text processing
 * (no database): only plain table / index definitions are accepted.
 */
final class SchemaScript {

    static final int MAX_LENGTH = 100_000;
    static final int MAX_STATEMENTS = 200;

    private static final Pattern ALLOWED_START = Pattern.compile(
            "^(CREATE\\s+TABLE\\b|CREATE\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\b|ALTER\\s+TABLE\\b"
                    + "|INSERT\\s+(IGNORE\\s+)?INTO\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INSERT_START = Pattern.compile("^INSERT\\b", Pattern.CASE_INSENSITIVE);

    /** Keywords that could read other data, touch the file system or reach other servers. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(SELECT|DIRECTORY|CONNECTION|FEDERATED|TABLESPACE|SLEEP|BENCHMARK|LOAD_FILE|OUTFILE|DUMPFILE)\\b",
            Pattern.CASE_INSENSITIVE);

    private SchemaScript() {
    }

    private static final Pattern INDEX_STATEMENT = Pattern.compile(
            "^(CREATE\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\b|ALTER\\s+TABLE\\b[\\s\\S]*\\bADD\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?(INDEX|KEY)\\b)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Validates the statement of a "what if" comparison: exactly one CREATE INDEX or ALTER TABLE ... ADD INDEX
     * (with the same safety checks as the schema box). Returns it without trailing ';'.
     */
    static String singleIndexStatement(String ddl) {
        List<String> statements = parse(ddl);
        if (statements.size() != 1 || !INDEX_STATEMENT.matcher(QueryValidator.scan(statements.get(0), true).trim()).find()) {
            throw new InvalidQueryException("A what-if comparison needs exactly one CREATE INDEX (or ALTER TABLE ... ADD INDEX) statement.");
        }
        return statements.get(0);
    }

    /** True for the INSERT statements (sample rows) in the list returned by {@link #parse}. */
    static boolean isInsert(String statement) {
        return INSERT_START.matcher(statement).find();
    }

    // ------------------------------------------------------------------

    /**
     * Validates the pasted schema and splits it into single statements.
     * Returns an empty list when nothing was pasted.
     *
     * @throws InvalidQueryException when something other than plain table / index definitions is found
     */
    static List<String> parse(String schema) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        if (schema.length() > MAX_LENGTH) {
            throw new InvalidQueryException("Schema is too long (max " + MAX_LENGTH + " characters).");
        }
        if (schema.contains("/*!")) {
            throw new InvalidQueryException("MySQL executable comments (/*! ... */) are not allowed.");
        }

        List<String> statements = split(QueryValidator.scan(schema, false));
        if (statements.size() > MAX_STATEMENTS) {
            throw new InvalidQueryException("Too many schema statements (max " + MAX_STATEMENTS + ").");
        }

        for (int i = 0; i < statements.size(); i++) {
            check(statements.get(i), i + 1);
        }
        return statements;
    }

    private static void check(String statement, int number) {
        String sanitized = QueryValidator.scan(statement, true).trim();
        String where = "Schema statement " + number + ": ";

        if (!ALLOWED_START.matcher(sanitized).find()) {
            throw new InvalidQueryException(where
                    + "only CREATE TABLE, CREATE INDEX, ALTER TABLE and INSERT INTO are allowed in the schema box.");
        }
        var forbidden = FORBIDDEN.matcher(sanitized);
        if (forbidden.find()) {
            throw new InvalidQueryException(where + "'" + forbidden.group(1).toUpperCase(Locale.ROOT)
                    + "' is not allowed in the schema box.");
        }
        if (hasQualifiedName(sanitized)) {
            throw new InvalidQueryException(where
                    + "use plain table names (orders), not database-qualified ones (shop.orders).");
        }
    }

    /** A '.' that is not the decimal point of a number (digits on both sides) means db.table. */
    private static boolean hasQualifiedName(String sanitized) {
        for (int i = 0; i < sanitized.length(); i++) {
            if (sanitized.charAt(i) != '.') {
                continue;
            }
            boolean number = i > 0 && i + 1 < sanitized.length()
                    && Character.isDigit(sanitized.charAt(i - 1))
                    && Character.isDigit(sanitized.charAt(i + 1));
            if (!number) {
                return true;
            }
        }
        return false;
    }

    /** Splits on ';' that are outside quotes. Comments are already removed. */
    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = SqlText.skipQuoted(text, i);
            } else if (c == ';') {
                out.add(text.substring(start, i).trim());
                i++;
                start = i;
            } else {
                i++;
            }
        }
        out.add(text.substring(Math.min(start, text.length())).trim());
        out.removeIf(String::isEmpty);
        return out;
    }
}
