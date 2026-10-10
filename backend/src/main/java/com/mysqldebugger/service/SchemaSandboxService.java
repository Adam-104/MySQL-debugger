package com.mysqldebugger.service;

import com.mysqldebugger.exception.InvalidQueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Runs work inside a throw-away schema, so visitors can analyse their OWN tables and try "what if" indexes
 * without ever changing (or even touching) a real database.
 *
 * Two ways to fill the temporary schema:
 *  - {@link #withSandbox}: from CREATE TABLE / CREATE INDEX / ALTER TABLE / INSERT statements the visitor pasted;
 *  - {@link #withClonedDefaultSchema}: from a copy (structure + rows) of the configured database, for the demo tables.
 *
 * Everything runs on ONE pooled connection (held by a Spring transaction), so the plain JdbcTemplate used by the
 * rest of the code transparently sees the sandbox as its default schema. The connection's original default schema
 * is restored, and the sandbox dropped, before the connection goes back to the pool.
 */
@Service
public class SchemaSandboxService {

    private static final Logger log = LoggerFactory.getLogger(SchemaSandboxService.class);

    private static final int MAX_CONCURRENT = 4;
    /** Limits for copying the configured database (what-if on the demo tables). */
    static final int MAX_CLONE_TABLES = 20;
    static final long MAX_CLONE_ROWS = 500_000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);
    /** Fixed schema names (see {@code app.sandbox.schemas}); null means "use a random sbx_<uuid> name per request". */
    private final ConcurrentLinkedQueue<String> fixedNames;

    /**
     * @param fixedSchemas optional comma-separated schema names to reuse as sandboxes (property
     *                     {@code app.sandbox.schemas}, env {@code APP_SANDBOX_SCHEMAS}). Needed on hosts that do not
     *                     support wildcard grants such as {@code sbx\_%} (for example MySQL with partial_revokes=ON):
     *                     grant the database user ALL privileges on exactly these names instead. Each name serves one
     *                     request at a time, so the number of names is the number of concurrent analyses.
     */
    public SchemaSandboxService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                @Value("${app.sandbox.schemas:}") String fixedSchemas) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.fixedNames = parseNames(fixedSchemas);
    }

    static ConcurrentLinkedQueue<String> parseNames(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        List<String> names = Arrays.stream(csv.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
        for (String n : names) {
            if (!n.matches("[A-Za-z0-9_]{1,40}")) {
                throw new IllegalArgumentException("Invalid sandbox schema name: " + n);
            }
        }
        return names.isEmpty() ? null : new ConcurrentLinkedQueue<>(names);
    }

    /** Fills the sandbox; {@code original} is the connection's default schema before the sandbox was selected. */
    private interface Setup {
        void apply(String sandbox, String original);
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /**
     * Runs {@code work} with the visitor's schema available as the default schema. When no schema was supplied,
     * {@code work} simply runs against the configured database.
     */
    public <T> T withSandbox(String schemaSql, Supplier<T> work) {
        List<String> statements = SchemaScript.parse(schemaSql);
        if (statements.isEmpty()) {
            return work.get();
        }
        boolean hasRows = statements.stream().anyMatch(SchemaScript::isInsert);
        return run((sandbox, original) -> {
            for (int i = 0; i < statements.size(); i++) {
                try {
                    jdbc.execute(statements.get(i));
                } catch (DataAccessException e) {
                    throw new InvalidQueryException("Schema statement " + (i + 1) + " failed: " + clean(e, sandbox));
                }
            }
            if (hasRows) {
                refreshStatistics(sandbox);
            }
        }, work);
    }

    /**
     * Runs {@code work} against a temporary copy (structure and rows) of the configured database, so changes such as
     * a new index never touch the real one.
     */
    public <T> T withClonedDefaultSchema(Supplier<T> work) {
        return run(this::cloneInto, work);
    }

    /** Executes one statement (already validated by the caller) in the current default schema. */
    public void executeInCurrentSchema(String statement, String errorPrefix) {
        try {
            jdbc.execute(statement);
        } catch (DataAccessException e) {
            throw new InvalidQueryException(errorPrefix + String.valueOf(e.getMostSpecificCause().getMessage()));
        }
    }

    /** Refreshes the optimizer statistics of every table in the current default schema (best effort). */
    public void refreshCurrentSchemaStatistics() {
        try {
            refreshStatistics(jdbc.queryForObject("SELECT DATABASE()", String.class));
        } catch (RuntimeException e) {
            log.debug("Could not refresh statistics", e);
        }
    }

    // ------------------------------------------------------------------
    // Sandbox lifecycle
    // ------------------------------------------------------------------

    private <T> T run(Setup setup, Supplier<T> work) {
        String fixed = null;
        if (fixedNames != null) {
            fixed = fixedNames.poll();
            if (fixed == null) {
                throw busy();
            }
        } else if (!slots.tryAcquire()) {
            throw busy();
        }
        try {
            final String name = fixed != null ? fixed : "sbx_" + UUID.randomUUID().toString().replace("-", "");
            return tx.execute(status -> inSandbox(name, setup, work));
        } finally {
            if (fixed != null) {
                fixedNames.add(fixed);
            } else {
                slots.release();
            }
        }
    }

    private static InvalidQueryException busy() {
        return new InvalidQueryException("The server is busy analysing other schemas. Please try again in a moment.");
    }

    private <T> T inSandbox(String name, Setup setup, Supplier<T> work) {
        String original = jdbc.queryForObject("SELECT DATABASE()", String.class);
        try {
            // A fixed name may have been left behind by a crashed request; random names simply do not exist yet.
            jdbc.execute("DROP DATABASE IF EXISTS " + quote(name));
            jdbc.execute("CREATE DATABASE " + quote(name));
            jdbc.execute("USE " + quote(name));

            setup.apply(name, original);

            try {
                return work.get();
            } catch (DataAccessException e) {
                String message = clean(e, name);
                if (message.toLowerCase().contains("exist")) {
                    message += availableTablesHint(name);
                }
                throw new InvalidQueryException(message);
            }
        } finally {
            try {
                jdbc.execute("DROP DATABASE IF EXISTS " + quote(name));
            } catch (RuntimeException e) {
                log.warn("Could not drop sandbox schema {}", name, e);
            }
            if (original != null) {
                try {
                    jdbc.execute("USE " + quote(original));
                } catch (RuntimeException e) {
                    log.warn("Could not restore default schema {}", original, e);
                }
            }
        }
    }

    /** Copies every base table (structure, indexes and rows) of the configured database into the sandbox. */
    private void cloneInto(String sandbox, String original) {
        if (original == null) {
            throw new InvalidQueryException("No default database is configured, so there is nothing to copy. "
                    + "Paste your own schema instead.");
        }
        // Names come from INFORMATION_SCHEMA, never from the visitor, and are backtick-quoted below.
        List<Object[]> tables = jdbc.query(
                "SELECT TABLE_NAME, COALESCE(TABLE_ROWS, 0) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME LIMIT ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getLong(2)},
                original, MAX_CLONE_TABLES + 1);

        if (tables.size() > MAX_CLONE_TABLES) {
            throw new InvalidQueryException("This database has more than " + MAX_CLONE_TABLES
                    + " tables, which is too many to copy for a what-if comparison. Paste your own schema instead.");
        }
        long rows = tables.stream().mapToLong(t -> (Long) t[1]).sum();
        if (rows > MAX_CLONE_ROWS) {
            throw new InvalidQueryException("This database has about " + rows + " rows, which is too many to copy "
                    + "for a what-if comparison (limit " + MAX_CLONE_ROWS + "). Paste your own schema instead.");
        }

        for (Object[] t : tables) {
            String source = quote(original) + "." + quote((String) t[0]);
            String target = quote(sandbox) + "." + quote((String) t[0]);
            jdbc.execute("CREATE TABLE " + target + " LIKE " + source);
            try {
                jdbc.execute("INSERT INTO " + target + " SELECT * FROM " + source);
            } catch (RuntimeException e) {
                // e.g. generated columns: keep the (empty) table rather than failing the whole comparison
                log.debug("Could not copy rows of {}", t[0], e);
            }
        }
        refreshStatistics(sandbox);
    }

    /** Sample rows were loaded: refresh the optimizer's statistics so EXPLAIN reflects them (best effort). */
    private void refreshStatistics(String schemaName) {
        try {
            List<String> tables = jdbc.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'",
                    String.class, schemaName);
            for (String table : tables) {
                jdbc.execute("ANALYZE TABLE " + quote(table));
            }
        } catch (RuntimeException e) {
            log.debug("ANALYZE TABLE failed in sandbox {}", schemaName, e);
        }
    }

    /** Explains schema mode when a query mentions a table that is not in the pasted schema. */
    private String availableTablesHint(String schemaName) {
        try {
            List<String> tables = jdbc.queryForList(
                    "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? "
                            + "AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME",
                    String.class, schemaName);
            return "\n\nSchema mode is on: only the tables from your pasted schema exist (" + String.join(", ", tables)
                    + "). The demo tables (customers, orders, ...) are not available here. Change the query to use "
                    + "your tables, or clear the schema box to use the demo database.";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** MySQL's message without the internal sandbox name. */
    private static String clean(DataAccessException e, String sandboxName) {
        return String.valueOf(e.getMostSpecificCause().getMessage()).replace(sandboxName + ".", "");
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }
}