package com.mysqldebugger.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaStatsServiceTest {

    @Test
    void quotesIdentifiersWithBackticks() {
        assertEquals("`orders`", SchemaStatsService.quote("orders"));
    }

    @Test
    void escapesEmbeddedBackticks() {
        assertEquals("`we``ird`", SchemaStatsService.quote("we`ird"));
    }

    @Test
    void groupsColumnsByTableKeepingOrder() {
        var grouped = SchemaStatsService.groupColumns(java.util.List.of(
                new String[]{"orders", "id"}, new String[]{"orders", "status"}, new String[]{"customers", "id"}));
        assertEquals(java.util.List.of("orders", "customers"), new java.util.ArrayList<>(grouped.keySet()));
        assertEquals(java.util.List.of("id", "status"), grouped.get("orders"));
        assertEquals(java.util.List.of("id"), grouped.get("customers"));
    }
}
