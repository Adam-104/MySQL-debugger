package com.mysqldebugger.service;

import com.mysqldebugger.model.StatementType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatementClassifierTest {

    private final StatementClassifier classifier = new StatementClassifier();

    @Test
    void classifiesQueriesAndDml() {
        assertEquals(StatementType.SELECT, classifier.classify("SELECT 1"));
        assertEquals(StatementType.SELECT, classifier.classify("(SELECT 1) UNION (SELECT 2)"));
        assertEquals(StatementType.INSERT, classifier.classify("insert into t values (1)"));
        assertEquals(StatementType.REPLACE, classifier.classify("REPLACE INTO t VALUES (1)"));
        assertEquals(StatementType.UPDATE, classifier.classify("UPDATE t SET a = 1"));
        assertEquals(StatementType.DELETE, classifier.classify("DELETE FROM t"));
    }

    @Test
    void classifiesWithByItsMainStatement() {
        assertEquals(StatementType.CTE_SELECT,
                classifier.classify("WITH a AS (SELECT 1) SELECT * FROM a"));
        assertEquals(StatementType.DELETE,
                classifier.classify("WITH a AS (SELECT 1) DELETE FROM t WHERE id IN (SELECT * FROM a)"));
    }

    @Test
    void classifiesDdlWeUnderstand() {
        assertEquals(StatementType.CREATE_VIEW,
                classifier.classify("CREATE OR REPLACE ALGORITHM=MERGE VIEW v AS SELECT 1"));
        assertEquals(StatementType.CREATE_TRIGGER,
                classifier.classify("CREATE DEFINER=`root`@`localhost` TRIGGER t BEFORE INSERT ON a FOR EACH ROW SET NEW.x = 1"));
        assertEquals(StatementType.CREATE_INDEX, classifier.classify("CREATE UNIQUE INDEX i ON t (a)"));
        assertEquals(StatementType.CREATE_INDEX, classifier.classify("ALTER TABLE t ADD INDEX i (a)"));
    }

    @Test
    void everythingElseIsUnsupported() {
        assertEquals(StatementType.UNSUPPORTED, classifier.classify("DROP TABLE t"));
        assertEquals(StatementType.UNSUPPORTED, classifier.classify("CREATE TABLE t (id int)"));
        assertEquals(StatementType.UNSUPPORTED, classifier.classify("ALTER TABLE t DROP COLUMN a"));
        assertEquals(StatementType.UNSUPPORTED, classifier.classify("TRUNCATE t"));
    }
}
