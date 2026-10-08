package com.mysqldebugger.model;

public enum StatementType {
    SELECT,          // plain SELECT (incl. joins, subqueries, window functions, UNION)
    CTE_SELECT,      // WITH ... SELECT
    INSERT,
    REPLACE,
    UPDATE,
    DELETE,
    CREATE_VIEW,
    CREATE_TRIGGER,
    CREATE_INDEX,    // CREATE INDEX or ALTER TABLE ... ADD INDEX
    UNSUPPORTED
}
