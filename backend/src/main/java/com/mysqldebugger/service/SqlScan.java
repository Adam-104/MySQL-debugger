package com.mysqldebugger.service;

/** Helpers that expose the validator's scanner to other parsers. */
final class SqlScan {

    private SqlScan() {
    }

    /** Removes comments and empties string literals / quoted identifiers. */
    static String blankLiterals(String sql) {
        return QueryValidator.scan(sql, true);
    }
}
