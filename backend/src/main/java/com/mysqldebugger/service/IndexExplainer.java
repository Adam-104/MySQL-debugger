package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import com.mysqldebugger.model.ColumnInfo;
import com.mysqldebugger.model.ExplainerResult;
import com.mysqldebugger.model.IndexInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Explains CREATE INDEX / ALTER TABLE ... ADD INDEX. */
@Service
public class IndexExplainer {

    /** Parsed index definition. */
    public record Definition(String name, String table, String kind, List<String> columns,
                             List<String> columnSpecs) {
    }

    private static final Pattern CREATE = Pattern.compile(
            "^\\s*CREATE\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?INDEX\\s+`?([\\w]+)`?"
                    + "(?:\\s+USING\\s+\\w+)?\\s+ON\\s+`?([\\w.]+)`?\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ALTER = Pattern.compile(
            "^\\s*ALTER\\s+TABLE\\s+`?([\\w.]+)`?\\s+ADD\\s+(UNIQUE\\s+|FULLTEXT\\s+|SPATIAL\\s+)?(?:INDEX|KEY)"
                    + "(?:\\s+`?([\\w]+)`?)?(?:\\s+USING\\s+\\w+)?\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Set<String> LOB_TYPES = Set.of(
            "text", "tinytext", "mediumtext", "longtext", "blob", "tinyblob", "mediumblob", "longblob", "json");
    private static final Set<String> LOW_CARDINALITY = Set.of("tinyint", "bit", "enum", "set", "boolean");

    public Definition parse(String sql) {

        String kindWord;
        String name;
        String table;
        int open;

        Matcher c = CREATE.matcher(sql);
        Matcher a = ALTER.matcher(sql);
        if (c.find()) {
            kindWord = c.group(1);
            name = c.group(2);
            table = c.group(3);
            open = c.end() - 1;
        } else if (a.find()) {
            table = a.group(1);
            kindWord = a.group(2);
            name = a.group(3);
            open = a.end() - 1;
        } else {
            throw new InvalidQueryException("Could not parse the index definition.");
        }

        int close = SqlText.matching(sql, open);
        if (close == -1) {
            throw new InvalidQueryException("Unbalanced parentheses in the index column list.");
        }

        List<String> specs = SqlText.splitTopLevel(sql.substring(open + 1, close));
        List<String> columns = new ArrayList<>();
        for (String spec : specs) {
            Matcher col = Pattern.compile("^`?(\\w+)`?").matcher(spec.trim());
            columns.add(col.find() ? col.group(1) : spec.trim());
        }
        if (columns.isEmpty()) {
            throw new InvalidQueryException("An index needs at least one column.");
        }

        String kind = kindWord == null ? "BTREE" : kindWord.trim().toUpperCase();
        String table1 = SqlText.stripQuotes(table);
        if (name == null) {
            name = "(auto-named: " + columns.get(0) + ")";
        }
        return new Definition(name, table1, kind, columns, specs);
    }

    public ExplainerResult explain(Definition def, List<IndexInfo> existing, Map<String, ColumnInfo> tableColumns) {

        boolean tableKnown = !tableColumns.isEmpty();
        boolean unique = def.kind().equals("UNIQUE");
        boolean fulltext = def.kind().equals("FULLTEXT");

        ExplainerResult r = new ExplainerResult();
        r.targetTable = def.table();
        r.summary = "Builds " + (unique ? "a UNIQUE " : fulltext ? "a FULLTEXT " : "an ") + "index '" + def.name()
                + "' on " + def.table() + " (" + String.join(", ", def.columns()) + ").";

        r.add("TARGET", "MySQL locates the table and the columns to index.",
                def.table() + " (" + String.join(", ", def.columnSpecs()) + ")",
                tableKnown ? "Table found in the current database with " + existing.size() + " existing index(es)."
                        : "Table not found in the current database, so this analysis uses only the SQL text.");

        r.add("BUILD INDEX",
                "InnoDB reads every row of the table, sorts the index keys in chunks (merge sort), and builds the "
                        + "B-tree from the bottom up.",
                "scan table → sort keys → write B-tree",
                "For a secondary index this is an online operation: the table stays readable and writable while it "
                        + "is built, with brief metadata locks at the start and end. Build time grows with table size.");

        if (unique) {
            r.add("UNIQUENESS CHECK", "While building, MySQL verifies that no two rows share the same key.",
                    String.join(", ", def.columns()),
                    "If duplicates exist the whole CREATE INDEX fails and nothing is changed.");
        }

        if (fulltext) {
            r.add("TOKENIZE", "Text is split into words and stored in an inverted index used by MATCH ... AGAINST.",
                    String.join(", ", def.columns()), "FULLTEXT indexes do not help with LIKE or equality searches.");
        } else {
            StringBuilder prefixes = new StringBuilder();
            for (int i = 1; i <= def.columns().size(); i++) {
                if (i > 1) prefixes.append("   ");
                prefixes.append("(").append(String.join(", ", def.columns().subList(0, i))).append(")");
            }
            r.add("USABLE PREFIXES",
                    "A B-tree index is sorted by the first column, then by the second inside it, and so on. "
                            + "Queries can use it only through a leftmost prefix of the columns.",
                    prefixes.toString(),
                    "A filter on only the last column cannot seek in this index.");
        }

        r.add("MAINTENANCE", "After the build, every INSERT, DELETE and UPDATE of an indexed column also updates "
                        + "this index.",
                "write cost on " + def.table(), "Reads get faster for matching queries; writes get slightly slower.");

        r.notes.add("An index is a separate sorted copy of some columns plus the primary key. MySQL can jump to a "
                + "value in O(log n) instead of reading every row, but only if the query's filter matches the "
                + "index's leftmost columns.");
        r.notes.add("The optimizer decides per query whether an index is worth using. When a value matches a large "
                + "part of the table, a full scan can be cheaper, so low-selectivity indexes are often ignored.");

        // ---- advice
        for (int i = 0; i < def.columns().size(); i++) {
            String col = def.columns().get(i);
            if (tableKnown && !tableColumns.containsKey(col.toLowerCase())) {
                r.issues.add(ExplainerResult.issue("WARNING", "Unknown Column '" + col + "'",
                        "Column '" + col + "' does not exist in table " + def.table() + ".",
                        "CREATE INDEX will fail with error 1072.",
                        "Check the column name (available: "
                                + tableColumns.values().stream().map(ColumnInfo::name).collect(Collectors.joining(", "))
                                + ")."));
                continue;
            }
            ColumnInfo info = tableColumns.get(col.toLowerCase());
            if (info == null) {
                continue;
            }
            String spec = def.columnSpecs().get(i);
            boolean hasPrefix = Pattern.compile("^`?\\w+`?\\s*\\(\\s*\\d+\\s*\\)").matcher(spec.trim()).find();
            String type = info.dataType() == null ? "" : info.dataType().toLowerCase();

            if (!fulltext && LOB_TYPES.contains(type) && !hasPrefix) {
                r.issues.add(ExplainerResult.issue("WARNING", "TEXT/BLOB Column Needs A Prefix Length",
                        "Column '" + col + "' is " + type.toUpperCase() + ".",
                        "MySQL cannot index the full value of TEXT/BLOB columns; the statement fails with error 1170.",
                        "Index a prefix, e.g. " + col + "(50), or use a FULLTEXT index for text search."));
            }
            if (!fulltext && LOW_CARDINALITY.contains(type) && def.columns().size() == 1) {
                r.issues.add(ExplainerResult.issue("NOTICE", "Low-Selectivity Column",
                        "Column '" + col + "' (" + type + ") typically holds only a few distinct values.",
                        "If each value matches a large share of the rows, MySQL will prefer a full scan and the "
                                + "index only costs write performance and disk space.",
                        "Combine it with a more selective column, e.g. (customer_id, " + col + ")."));
            }
            if (info.maxLength() != null && info.maxLength() > 191 && !hasPrefix && type.contains("char")) {
                r.issues.add(ExplainerResult.issue("INFO", "Long VARCHAR Key",
                        "Column '" + col + "' is " + type + "(" + info.maxLength() + ").",
                        "Large keys make the index bigger (utf8mb4 uses up to 4 bytes per character), so fewer entries "
                                + "fit per page and lookups touch more pages.",
                        "Use a prefix length such as " + col + "(32) if the leading characters are selective."));
            }
        }

        if (def.columns().size() > 4) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Very Wide Index",
                    "The index has " + def.columns().size() + " columns.",
                    "Wide indexes are large, slow down writes and are only useful for queries that filter on the "
                            + "leftmost columns.",
                    "Keep composite indexes to the columns real queries filter and sort on."));
        }

        for (IndexInfo e : existing) {
            List<String> ec = e.columns();
            if (ec.equals(lower(def.columns()))) {
                r.issues.add(ExplainerResult.issue("WARNING", "Duplicate Of Existing Index",
                        "Index '" + e.name() + "' already covers exactly these columns.",
                        "A second identical index doubles the write and storage cost and gives the optimizer nothing new.",
                        "Do not create it, or drop '" + e.name() + "' if you intend to replace it."));
            } else if (startsWith(ec, lower(def.columns()))) {
                r.issues.add(ExplainerResult.issue("WARNING", "Redundant: Covered By '" + e.name() + "'",
                        "Existing index '" + e.name() + "' (" + String.join(", ", ec) + ") already starts with these columns.",
                        "Any query this index could serve can already use the longer index through its leftmost prefix.",
                        "Skip creating it."));
            } else if (startsWith(lower(def.columns()), ec) && !e.name().equalsIgnoreCase("PRIMARY") && !e.unique()) {
                r.issues.add(ExplainerResult.issue("INFO", "Makes '" + e.name() + "' Redundant",
                        "The new index starts with the columns of existing index '" + e.name() + "'.",
                        "The shorter index can be served by the longer one, so keeping both only adds write cost.",
                        "Consider dropping '" + e.name() + "' after creating the new index."));
            }
        }

        if (existing.stream().map(IndexInfo::name).distinct().count() >= 5) {
            r.issues.add(ExplainerResult.issue("NOTICE", "Table Already Has Many Indexes",
                    def.table() + " already has " + existing.stream().map(IndexInfo::name).distinct().count() + " indexes.",
                    "Every index must be updated on each INSERT, UPDATE of indexed columns and DELETE, so write cost "
                            + "grows with every index you add.",
                    "Drop indexes that no query uses before adding more."));
        }

        return r;
    }

    private static List<String> lower(List<String> cols) {
        return cols.stream().map(String::toLowerCase).toList();
    }

    private static boolean startsWith(List<String> longer, List<String> prefix) {
        if (prefix.size() > longer.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!longer.get(i).equalsIgnoreCase(prefix.get(i))) return false;
        }
        return true;
    }
}
