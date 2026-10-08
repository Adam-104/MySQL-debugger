package com.mysqldebugger.service;

import com.mysqldebugger.model.ColumnInfo;
import com.mysqldebugger.model.ExplainResult;
import com.mysqldebugger.model.IndexInfo;
import com.mysqldebugger.model.JsonExecutionPlan;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DebugService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonExecutionPlanService jsonExecutionPlanService;

    public DebugService(
            JdbcTemplate jdbcTemplate,
            JsonExecutionPlanService jsonExecutionPlanService) {

        this.jdbcTemplate = jdbcTemplate;
        this.jsonExecutionPlanService = jsonExecutionPlanService;
    }

    // --------------------------------------------------
    // Traditional EXPLAIN
    // --------------------------------------------------

    public List<ExplainResult> debugQuery(String query) {

        String explainQuery = "EXPLAIN " + query;

        return jdbcTemplate.query(
                explainQuery,
                (rs, rowNum) -> {

                    ExplainResult result =
                            new ExplainResult();

                    result.setId(
                            rs.getLong("id")
                    );

                    result.setSelectType(
                            rs.getString("select_type")
                    );

                    result.setTable(
                            rs.getString("table")
                    );

                    result.setPartitions(
                            rs.getString("partitions")
                    );

                    result.setType(
                            rs.getString("type")
                    );

                    result.setPossibleKeys(
                            rs.getString("possible_keys")
                    );

                    result.setKey(
                            rs.getString("key")
                    );

                    result.setKeyLength(
                            rs.getString("key_len")
                    );

                    result.setRef(
                            rs.getString("ref")
                    );

                    result.setRows(
                            rs.getLong("rows")
                    );

                    result.setFiltered(
                            rs.getDouble("filtered")
                    );

                    result.setExtra(
                            rs.getString("Extra")
                    );

                    return result;
                }
        );
    }


    // --------------------------------------------------
    // EXPLAIN FORMAT=JSON
    // --------------------------------------------------

    public JsonExecutionPlan debugJsonQuery(String query) {

        String explainJsonQuery =
                "EXPLAIN FORMAT=JSON " + query;

        String json = jdbcTemplate.queryForObject(
                explainJsonQuery,
                String.class
        );

        return jsonExecutionPlanService.parse(json);
    }

    // --------------------------------------------------
    // Schema metadata (read from INFORMATION_SCHEMA; best effort)
    // --------------------------------------------------

    private record IndexRow(String name, String column, boolean unique) {
    }

    public List<IndexInfo> existingIndexes(String table) {

        try {
            List<IndexRow> rows = jdbcTemplate.query(
                    "SELECT index_name, column_name, non_unique "
                            + "FROM information_schema.statistics "
                            + "WHERE table_schema = DATABASE() AND table_name = ? "
                            + "ORDER BY index_name, seq_in_index",
                    (rs, n) -> new IndexRow(rs.getString(1), rs.getString(2), rs.getInt(3) == 0),
                    table);

            Map<String, List<String>> columns = new LinkedHashMap<>();
            Map<String, Boolean> unique = new LinkedHashMap<>();
            for (IndexRow row : rows) {
                if (row.column() == null) {
                    continue; // functional index part
                }
                columns.computeIfAbsent(row.name(), k -> new ArrayList<>()).add(row.column().toLowerCase());
                unique.put(row.name(), row.unique());
            }

            List<IndexInfo> result = new ArrayList<>();
            columns.forEach((name, cols) -> result.add(new IndexInfo(name, cols, unique.get(name))));
            return result;

        } catch (DataAccessException e) {
            return List.of();
        }
    }

    public Map<String, ColumnInfo> tableColumns(String table) {

        Map<String, ColumnInfo> result = new LinkedHashMap<>();
        try {
            List<ColumnInfo> columns = jdbcTemplate.query(
                    "SELECT column_name, data_type, character_maximum_length "
                            + "FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND table_name = ? "
                            + "ORDER BY ordinal_position",
                    (rs, n) -> {
                        long len = rs.getLong(3);
                        return new ColumnInfo(rs.getString(1), rs.getString(2), rs.wasNull() ? null : len);
                    },
                    table);

            for (ColumnInfo c : columns) {
                result.put(c.name().toLowerCase(), c);
            }
        } catch (DataAccessException e) {
            // metadata is optional
        }
        return result;
    }

    /** Descriptions like "audit_orders (AFTER INSERT)" of triggers defined on a table. */
    public List<String> triggersOn(String table) {

        try {
            return jdbcTemplate.query(
                    "SELECT trigger_name, action_timing, event_manipulation "
                            + "FROM information_schema.triggers "
                            + "WHERE trigger_schema = DATABASE() AND event_object_table = ? "
                            + "ORDER BY action_order",
                    (rs, n) -> rs.getString(1) + " (" + rs.getString(2) + " " + rs.getString(3) + ")",
                    table);
        } catch (DataAccessException e) {
            return List.of();
        }
    }
}
