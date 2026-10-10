package com.mysqldebugger.model;

/** A table in the connected schema with its exact row count and column count. */
public record TableStats(String name, long rows, int columns) {
}
