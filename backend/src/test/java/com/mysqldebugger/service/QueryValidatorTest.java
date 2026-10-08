package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryValidatorTest {

    private final QueryValidator validator = new QueryValidator();

    @Test
    void trimsAndStripsTrailingSemicolon() {
        assertEquals("SELECT * FROM orders", validator.validate("  SELECT * FROM orders;  "));
    }

    @Test
    void removesComments() {
        assertEquals("SELECT 1",
                validator.validate("SELECT /* hi */ 1 -- trailing\n").replaceAll("\\s+", " "));
    }

    @Test
    void ignoresKeywordsInsideStringsAndQuotedIdentifiers() {
        assertDoesNotThrow(() ->
                validator.validate("SELECT 'SLEEP(5); DROP TABLE x; --' AS note, `sleep` FROM t"));
    }

    @Test
    void rejectsEmpty() {
        assertThrows(InvalidQueryException.class, () -> validator.validate("  "));
        assertThrows(InvalidQueryException.class, () -> validator.validate(null));
    }

    @Test
    void rejectsStackedStatements() {
        assertThrows(InvalidQueryException.class,
                () -> validator.validate("SELECT 1; DROP TABLE orders"));
        assertThrows(InvalidQueryException.class,
                () -> validator.validate("DELETE FROM a; DELETE FROM b"));
    }

    @Test
    void rejectsHiddenExecutableComments() {
        assertThrows(InvalidQueryException.class,
                () -> validator.validate("SELECT 1 /*!50000 UNION SELECT 2 */"));
    }

    @Test
    void rejectsDangerousFunctionsAndOutfile() {
        assertThrows(InvalidQueryException.class, () -> validator.validate("SELECT SLEEP(10)"));
        assertThrows(InvalidQueryException.class,
                () -> validator.validate("SELECT * FROM t INTO OUTFILE '/tmp/x'"));
    }

    @Test
    void allowsSemicolonsInsideTriggerBodies() {
        String sql = "CREATE TRIGGER t1 BEFORE INSERT ON orders FOR EACH ROW BEGIN SET NEW.a = 1; SET NEW.b = 2; END";
        assertDoesNotThrow(() -> validator.validate(sql));
    }

    @Test
    void handlesDelimiterDirectives() {
        String sql = "DELIMITER $$\nCREATE TRIGGER t1 AFTER INSERT ON orders FOR EACH ROW\nBEGIN\n SET @x = 1;\nEND$$\nDELIMITER ;";
        String cleaned = validator.validate(sql);
        assertTrue(cleaned.startsWith("CREATE TRIGGER"));
        assertFalse(cleaned.contains("$$"));
        assertFalse(cleaned.toUpperCase().contains("DELIMITER"));
    }
}
