package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaScriptTest {

    @Test
    void emptyInputMeansNoSandbox() {
        assertTrue(SchemaScript.parse(null).isEmpty());
        assertTrue(SchemaScript.parse("  \n ").isEmpty());
    }

    @Test
    void splitsStatementsAndIgnoresCommentsAndSemicolonsInStrings() {
        String schema = """
                -- my shop
                CREATE TABLE users (id INT PRIMARY KEY, note VARCHAR(50) DEFAULT 'a;b'); /* c */
                CREATE INDEX idx_note ON users (note);
                ALTER TABLE users ADD COLUMN price DECIMAL(10,2) DEFAULT 0.5;
                """;
        List<String> statements = SchemaScript.parse(schema);
        assertEquals(3, statements.size());
        assertTrue(statements.get(0).contains("'a;b'"));
        assertTrue(statements.get(1).startsWith("CREATE INDEX"));
    }

    @Test
    void rejectsAnythingThatIsNotTableOrIndexDefinition() {
        for (String bad : List.of(
                "DROP TABLE users",
                "UPDATE users SET id = 1",
                "DELETE FROM users",
                "GRANT ALL ON *.* TO x",
                "CREATE DATABASE other",
                "CREATE TRIGGER t BEFORE INSERT ON users FOR EACH ROW SET NEW.a = 1")) {
            assertThrows(InvalidQueryException.class, () -> SchemaScript.parse(bad), bad);
        }
    }

    @Test
    void rejectsDataCopyingFileAccessAndQualifiedNames() {
        for (String bad : List.of(
                "CREATE TABLE t AS SELECT * FROM mysql.user",
                "INSERT INTO t SELECT * FROM mysql.user",
                "INSERT INTO t VALUES (LOAD_FILE('/etc/passwd'))",
                "INSERT INTO other.t VALUES (1)",
                "CREATE TABLE t (id INT) DATA DIRECTORY = '/tmp'",
                "CREATE TABLE t (id INT) ENGINE=FEDERATED CONNECTION='mysql://u@h/db/t'",
                "CREATE TABLE mysql.evil (id INT)",
                "CREATE TABLE t (id INT, FOREIGN KEY (id) REFERENCES `other`.`x` (id))",
                "CREATE TABLE t (id INT); DROP TABLE t")) {
            assertThrows(InvalidQueryException.class, () -> SchemaScript.parse(bad), bad);
        }
    }

    @Test
    void rejectsExecutableCommentsAndOversizedInput() {
        assertThrows(InvalidQueryException.class,
                () -> SchemaScript.parse("CREATE TABLE t (id INT) /*!50000 ; DROP TABLE x */"));
        assertThrows(InvalidQueryException.class,
                () -> SchemaScript.parse("CREATE TABLE t (id INT);".repeat(5000)));
    }

    @Test
    void acceptsPlainInsertsAsSampleRows() {
        List<String> statements = SchemaScript.parse(
                "CREATE TABLE t (id INT, note VARCHAR(20));"
                        + "INSERT INTO t (id, note) VALUES (1, 'a;b'), (2, 'it''s');"
                        + "INSERT IGNORE INTO t VALUES (3, NULL);");
        assertEquals(3, statements.size());
        assertTrue(!SchemaScript.isInsert(statements.get(0)));
        assertTrue(SchemaScript.isInsert(statements.get(1)));
        assertTrue(SchemaScript.isInsert(statements.get(2)));
    }

    @Test
    void whatIfAcceptsExactlyOneIndexStatement() {
        assertEquals("CREATE INDEX idx_o ON orders (status, created_at)",
                SchemaScript.singleIndexStatement("CREATE INDEX idx_o ON orders (status, created_at);"));
        assertTrue(SchemaScript.singleIndexStatement("ALTER TABLE orders ADD INDEX idx_s (status)").startsWith("ALTER TABLE"));
        assertTrue(SchemaScript.singleIndexStatement("create unique index u on t (a)").startsWith("create unique"));
        for (String bad : List.of(
                "",
                "CREATE TABLE t (id INT)",
                "DROP INDEX idx ON t",
                "ALTER TABLE t DROP COLUMN a",
                "CREATE INDEX a ON t (x); CREATE INDEX b ON t (y)",
                "CREATE INDEX a ON mysql.user (host)",
                "INSERT INTO t VALUES (1)")) {
            assertThrows(InvalidQueryException.class, () -> SchemaScript.singleIndexStatement(bad), bad);
        }
    }

    @Test
    void limitsStatementCount() {
        String many = "CREATE TABLE t (id INT);\n".repeat(SchemaScript.MAX_STATEMENTS + 1);
        assertThrows(InvalidQueryException.class, () -> SchemaScript.parse(many));
    }
}
