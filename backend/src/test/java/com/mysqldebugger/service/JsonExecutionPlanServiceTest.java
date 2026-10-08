package com.mysqldebugger.service;

import com.mysqldebugger.model.JsonExecutionPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonExecutionPlanServiceTest {

    private final JsonExecutionPlanService service =
            new JsonExecutionPlanService(
                    new com.fasterxml.jackson.databind.ObjectMapper()
            );

    @Test
    void shouldParseMysqlJsonExecutionPlan() {

        String json = """
                {
                  "query_block": {
                    "select_id": 1,
                    "cost_info": {
                      "query_cost": "3.40"
                    },
                    "ordering_operation": {
                      "using_filesort": true,
                      "grouping_operation": {
                        "using_temporary_table": true,
                        "using_filesort": false
                      }
                    }
                  }
                }
                """;

        JsonExecutionPlan result =
                service.parse(json);

        assertNotNull(result);

        assertEquals(1, result.getSelectId());

        assertEquals(
                3.40,
                result.getQueryCost(),
                0.001
        );

        assertTrue(
                result.isOrderingUsingFilesort()
        );

        assertTrue(
                result.isGroupingUsingTemporaryTable()
        );

        assertFalse(
                result.isGroupingUsingFilesort()
        );
    }
}