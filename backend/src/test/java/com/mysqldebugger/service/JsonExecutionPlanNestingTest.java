package com.mysqldebugger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysqldebugger.model.JsonExecutionPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonExecutionPlanNestingTest {

    private final JsonExecutionPlanService service =
            new JsonExecutionPlanService(new ObjectMapper());

    @Test
    void findsGroupingOperationDirectlyUnderQueryBlock() {
        String json = """
                {"query_block": {"select_id": 1,
                  "cost_info": {"query_cost": "12.50"},
                  "grouping_operation": {"using_temporary_table": true, "using_filesort": true}}}
                """;

        JsonExecutionPlan plan = service.parse(json);

        assertEquals(12.5, plan.getQueryCost(), 0.001);
        assertFalse(plan.isOrderingUsingFilesort());
        assertTrue(plan.isGroupingUsingTemporaryTable());
        assertTrue(plan.isGroupingUsingFilesort());
    }

    @Test
    void invalidJsonThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> service.parse("not json"));
    }
}
