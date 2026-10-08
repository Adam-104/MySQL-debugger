package com.mysqldebugger.service;

import com.mysqldebugger.model.ExecutionStep;
import com.mysqldebugger.model.ExplainResult;
import com.mysqldebugger.model.IndexInfo;
import com.mysqldebugger.model.IndexSuggestion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Suggests CREATE INDEX statements for tables that EXPLAIN shows being scanned,
 * following the usual rule: equality columns first, then sort columns (or one range column).
 */
@Component
public class IndexSuggester {

    private static final Set<String> NOT_ALIASES = Set.of(
            "ON", "USING", "WHERE", "GROUP", "ORDER", "LIMIT", "HAVING", "LEFT", "RIGHT", "INNER", "CROSS",
            "NATURAL", "JOIN", "STRAIGHT_JOIN", "SET", "UNION", "FOR", "WINDOW", "PARTITION", "USE", "FORCE",
            "IGNORE", "OUTER", "FULL", "LOCK", "AS");

    private static final Pattern TABLE_REF = Pattern.compile(
            "(?:^|\\bFROM\\b|\\bJOIN\\b|,)\\s*([A-Za-z_][\\w.]*)(?:\\s+(?:AS\\s+)?([A-Za-z_]\\w*))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMPARE = Pattern.compile(
            "(?<![\\w.])((?:\\w+\\.)?[A-Za-z_]\\w*)\\s*(<=>|>=|<=|<>|!=|=|>|<)\\s*((?:\\w+\\.)?[A-Za-z_]\\w*(?!\\s*\\())?");
    private static final Pattern IN_LIST = Pattern.compile(
            "(?<![\\w.])((?:\\w+\\.)?[A-Za-z_]\\w*)\\s+IN\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern BETWEEN = Pattern.compile(
            "(?<![\\w.])((?:\\w+\\.)?[A-Za-z_]\\w*)\\s+BETWEEN\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIKE_PREFIX = Pattern.compile(
            "(?<![\\w.])((?:\\w+\\.)?[A-Za-z_]\\w*)\\s+LIKE\\s+'[^%_']", Pattern.CASE_INSENSITIVE);

    private static class Columns {
        final Set<String> equality = new LinkedHashSet<>();
        final Set<String> range = new LinkedHashSet<>();
    }

    public List<IndexSuggestion> suggest(
            List<ExplainResult> rows,
            List<ExecutionStep> flow,
            Function<String, List<IndexInfo>> existingIndexes) {

        List<IndexSuggestion> out = new ArrayList<>();
        if (rows == null || rows.isEmpty() || flow == null) {
            return out;
        }

        Map<String, String> aliasToTable = aliasMap(flow);
        boolean singleTable = new LinkedHashSet<>(aliasToTable.values()).size() <= 1;

        String where = expr(flow, "WHERE");
        String join = expr(flow, "JOIN");
        String order = expr(flow, "ORDER BY");
        String group = expr(flow, "GROUP BY");

        Set<String> done = new LinkedHashSet<>();

        // ORDER BY may reference a SELECT alias (e.g. "revenue"), which is not a real column
        Set<String> selectAliases = new LinkedHashSet<>();
        Matcher al = Pattern.compile("\\bAS\\s+`?(\\w+)`?", Pattern.CASE_INSENSITIVE).matcher(expr(flow, "SELECT"));
        while (al.find()) {
            selectAliases.add(al.group(1).toLowerCase());
        }

        for (ExplainResult row : rows) {

            String t = row.getTable();
            if (t == null || t.startsWith("<")) {
                continue;
            }
            boolean scan = "ALL".equalsIgnoreCase(row.getType());
            boolean filesort = row.getExtra() != null && row.getExtra().contains("Using filesort");
            if (!scan && !(filesort && singleTable)) {
                continue;
            }

            String alias = t.toLowerCase();
            String table = aliasToTable.getOrDefault(alias, t);

            Columns cols = new Columns();
            collect(where, alias, aliasToTable, singleTable, cols);
            collect(join, alias, aliasToTable, singleTable, cols);

            List<String> sortCols = simpleColumns(!order.isEmpty() ? order : group, alias, singleTable, selectAliases);

            List<String> index = new ArrayList<>(cols.equality);
            String sortPart = null;
            String rangePart = null;

            if (cols.range.isEmpty() && !sortCols.isEmpty()) {
                for (String c : sortCols) {
                    if (!index.contains(c)) index.add(c);
                }
                sortPart = String.join(", ", sortCols);
            } else if (!cols.range.isEmpty()) {
                String rc = cols.range.iterator().next();
                if (!index.contains(rc)) index.add(rc);
                rangePart = rc;
            }

            if (index.isEmpty()) {
                continue;
            }
            if (index.size() > 4) {
                index = new ArrayList<>(index.subList(0, 4));
            }

            String key = table.toLowerCase() + ":" + index;
            if (!done.add(key)) {
                continue;
            }

            // skip if an existing index already starts with these columns
            List<IndexInfo> existing = existingIndexes == null ? List.of() : existingIndexes.apply(table);
            boolean covered = false;
            for (IndexInfo e : existing) {
                if (e.columns().size() >= index.size()) {
                    boolean prefix = true;
                    for (int i = 0; i < index.size(); i++) {
                        if (!e.columns().get(i).equalsIgnoreCase(index.get(i))) {
                            prefix = false;
                            break;
                        }
                    }
                    if (prefix) {
                        covered = true;
                        break;
                    }
                }
            }
            if (covered) {
                continue;
            }

            String name = ("idx_" + table + "_" + String.join("_", index)).replaceAll("[^A-Za-z0-9_]", "_");
            if (name.length() > 64) {
                name = name.substring(0, 64);
            }
            String ddl = "CREATE INDEX " + name + " ON " + table + " (" + String.join(", ", index) + ");";

            StringBuilder reason = new StringBuilder();
            if (!cols.equality.isEmpty()) {
                reason.append("Columns (").append(String.join(", ", cols.equality))
                        .append(") are compared with = / IN, so with this index MySQL can seek straight to the "
                                + "matching entries instead of reading all ~")
                        .append(row.getRows() > 0 ? row.getRows() : "all")
                        .append(" rows of ").append(table).append(". ");
            } else {
                reason.append("Without an index MySQL reads all ~")
                        .append(row.getRows() > 0 ? row.getRows() : "all").append(" rows of ").append(table).append(". ");
            }
            if (sortPart != null) {
                reason.append("The ORDER BY / GROUP BY columns (").append(sortPart)
                        .append(") come after the equality columns, so the index returns rows already ordered and the "
                                + "filesort / temporary table can disappear. ");
            }
            if (rangePart != null) {
                reason.append("The range column ").append(rangePart)
                        .append(" goes last because MySQL can use an index range on only one column after the "
                                + "equality columns.");
            }

            out.add(new IndexSuggestion(table, index, ddl, reason.toString().trim()));
        }

        return out;
    }

    // ------------------------------------------------------------------

    private static String expr(List<ExecutionStep> flow, String op) {
        for (ExecutionStep s : flow) {
            if (op.equalsIgnoreCase(s.getOperation()) && s.getExpression() != null) {
                return s.getExpression();
            }
        }
        return "";
    }

    /** alias (lower-case) or table name → real table name. */
    static Map<String, String> aliasMap(List<ExecutionStep> flow) {

        Map<String, String> map = new HashMap<>();
        StringBuilder sb = new StringBuilder();
        for (ExecutionStep s : flow) {
            String op = s.getOperation();
            if (("FROM".equalsIgnoreCase(op) || "JOIN".equalsIgnoreCase(op) || "TARGET".equalsIgnoreCase(op))
                    && s.getExpression() != null) {
                sb.append(' ').append(s.getExpression());
            }
        }

        String text = SqlText.mask(sb.toString().replace("`", ""));
        Matcher m = TABLE_REF.matcher(text);
        while (m.find()) {
            String table = SqlText.stripQuotes(m.group(1));
            if (NOT_ALIASES.contains(table.toUpperCase())) {
                continue;
            }
            map.put(table.toLowerCase(), table);
            String alias = m.group(2);
            if (alias != null && !NOT_ALIASES.contains(alias.toUpperCase())) {
                map.put(alias.toLowerCase(), table);
            }
        }
        return map;
    }

    private static void collect(String text, String alias, Map<String, String> aliasToTable,
                                boolean singleTable, Columns out) {

        if (text == null || text.isBlank()) {
            return;
        }
        // literals removed so values are never mistaken for columns
        String t = SqlScan.blankLiterals(text);

        Matcher m = COMPARE.matcher(t);
        while (m.find()) {
            String op = m.group(2);
            boolean eq = op.equals("=") || op.equals("<=>");
            boolean rng = op.equals(">") || op.equals("<") || op.equals(">=") || op.equals("<=");
            if (!eq && !rng) {
                continue;
            }
            addColumn(m.group(1), alias, singleTable, eq ? out.equality : out.range);
            if (m.group(3) != null && m.group(3).contains(".")) {
                addColumn(m.group(3), alias, singleTable, eq ? out.equality : out.range);
            }
        }

        Matcher in = IN_LIST.matcher(t);
        while (in.find()) {
            addColumn(in.group(1), alias, singleTable, out.equality);
        }
        Matcher bt = BETWEEN.matcher(t);
        while (bt.find()) {
            addColumn(bt.group(1), alias, singleTable, out.range);
        }
        Matcher like = LIKE_PREFIX.matcher(text);
        while (like.find()) {
            addColumn(like.group(1), alias, singleTable, out.range);
        }

        // a column that is compared with = cannot also be the range column
        out.range.removeAll(out.equality);
    }

    private static void addColumn(String ref, String alias, boolean singleTable, Set<String> target) {
        int dot = ref.indexOf('.');
        if (dot == -1) {
            if (singleTable) {
                target.add(ref);
            }
            return;
        }
        if (ref.substring(0, dot).equalsIgnoreCase(alias)) {
            target.add(ref.substring(dot + 1));
        }
    }

    /** Columns of an ORDER BY / GROUP BY list; empty if any item is not a plain column of this table. */
    private static List<String> simpleColumns(String text, String alias, boolean singleTable,
                                              Set<String> selectAliases) {

        List<String> cols = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return cols;
        }
        for (String item : SqlText.splitTopLevel(text)) {
            String it = item.replaceAll("(?i)\\s+(ASC|DESC)\\s*$", "").trim();
            Matcher m = Pattern.compile("^(?:(\\w+)\\.)?(\\w+)$").matcher(it);
            if (!m.find()) {
                return new ArrayList<>();
            }
            if (m.group(1) == null && selectAliases.contains(m.group(2).toLowerCase())) {
                return new ArrayList<>();
            }
            if (m.group(1) == null) {
                if (!singleTable) {
                    return new ArrayList<>();
                }
            } else if (!m.group(1).equalsIgnoreCase(alias)) {
                return new ArrayList<>();
            }
            cols.add(m.group(2));
        }
        return cols;
    }
}
