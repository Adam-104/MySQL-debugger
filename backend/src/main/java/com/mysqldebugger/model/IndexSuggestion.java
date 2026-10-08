package com.mysqldebugger.model;

import java.util.List;

public record IndexSuggestion(
        String table,
        List<String> columns,
        String ddl,
        String reason) {
}
