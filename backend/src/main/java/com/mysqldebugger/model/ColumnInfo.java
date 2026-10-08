package com.mysqldebugger.model;

/** A table column read from INFORMATION_SCHEMA. */
public record ColumnInfo(String name, String dataType, Long maxLength) {
}
