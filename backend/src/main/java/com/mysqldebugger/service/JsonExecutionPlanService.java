package com.mysqldebugger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysqldebugger.model.JsonExecutionPlan;
import org.springframework.stereotype.Service;

@Service
public class JsonExecutionPlanService {

    private final ObjectMapper objectMapper;

    public JsonExecutionPlanService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonExecutionPlan parse(String json) {

        try {

            JsonNode root = objectMapper.readTree(json);
            JsonNode queryBlock = root.path("query_block");

            JsonExecutionPlan plan = new JsonExecutionPlan();

            plan.setSelectId(queryBlock.path("select_id").asInt());

            JsonNode costInfo = queryBlock.path("cost_info");
            if (costInfo.has("query_cost")) {
                plan.setQueryCost(costInfo.path("query_cost").asDouble());
            }

            // MySQL nests these operations differently depending on the query
            // (e.g. grouping_operation may sit directly under query_block,
            // or inside ordering_operation), so search the whole tree.
            JsonNode ordering = findFirst(queryBlock, "ordering_operation");
            if (ordering != null) {
                plan.setOrderingUsingFilesort(
                        ordering.path("using_filesort").asBoolean(false));
            }

            JsonNode grouping = findFirst(queryBlock, "grouping_operation");
            if (grouping != null) {
                plan.setGroupingUsingTemporaryTable(
                        grouping.path("using_temporary_table").asBoolean(false));
                plan.setGroupingUsingFilesort(
                        grouping.path("using_filesort").asBoolean(false));
            }

            return plan;

        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Unable to parse MySQL JSON execution plan", e);
        }
    }

    /** Depth-first search for the first object field with the given name. */
    private static JsonNode findFirst(JsonNode node, String fieldName) {

        if (node == null) {
            return null;
        }

        if (node.isObject()) {
            if (node.has(fieldName)) {
                return node.get(fieldName);
            }
            for (JsonNode child : node) {
                JsonNode found = findFirst(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findFirst(child, fieldName);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }
}
