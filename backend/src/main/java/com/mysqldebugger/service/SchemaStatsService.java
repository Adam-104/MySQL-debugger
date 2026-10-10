package com.mysqldebugger.service;

import com.mysqldebugger.model.TableStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only overview of the tables in the connected database (names, column counts, row counts). */
@Service
public class SchemaStatsService {

    /** Upper bound so a huge schema cannot turn this call into dozens of COUNT(*) scans. */
    static final int MAX_TABLES = 50;

    /** Upper bound on the column names returned for editor autocomplete. */
    static final int MAX_COLUMN_ROWS = 2000;

    private final JdbcTemplate jdbc;

    public SchemaStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TableStats> tables() {
        // Table names come from INFORMATION_SCHEMA (not from the visitor), and are backtick-quoted below.
        List<String[]> found = jdbc.query(
                "SELECT t.TABLE_NAME, "
                        + "(SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS c "
                        + " WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME) "
                        + "FROM INFORMATION_SCHEMA.TABLES t "
                        + "WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE' "
                        + "ORDER BY t.TABLE_NAME LIMIT ?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                MAX_TABLES);

        List<TableStats> result = new ArrayList<>();
        for (String[] t : found) {
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + quote(t[0]), Long.class);
            result.add(new TableStats(t[0], rows == null ? 0 : rows, Integer.parseInt(t[1])));
        }
        return result;
    }

    /** Column names per table, used for editor autocomplete. Names come from INFORMATION_SCHEMA only. */
    public Map<String, List<String>> columns() {
        List<String[]> rows = jdbc.query(
                "SELECT c.TABLE_NAME, c.COLUMN_NAME "
                        + "FROM INFORMATION_SCHEMA.COLUMNS c "
                        + "JOIN INFORMATION_SCHEMA.TABLES t "
                        + "  ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME "
                        + "WHERE c.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE' "
                        + "ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION LIMIT ?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)},
                MAX_COLUMN_ROWS);
        return groupColumns(rows);
    }

    /** Groups (table, column) rows into table → ordered column list. */
    static Map<String, List<String>> groupColumns(List<String[]> rows) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String[] row : rows) {
            result.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
        }
        return result;
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}
