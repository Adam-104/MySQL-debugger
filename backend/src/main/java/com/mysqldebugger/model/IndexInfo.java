package com.mysqldebugger.model;

import java.util.List;

/** An existing index read from INFORMATION_SCHEMA. */
public record IndexInfo(String name, List<String> columns, boolean unique) {
}
