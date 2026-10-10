# 🐬 MySQL Query Debugger

A learning-and-tuning tool for MySQL. Paste a statement and it shows

1. **how MySQL executes it**, step by step (an interactive walk-through),
2. **what is slow and why that matters**, with a concrete fix for each finding, and
3. **which indexes to add**, as ready-to-run `CREATE INDEX` statements.

Built with **Spring Boot** (analysis API) and **React** (UI).

## What it understands

| Statement | What you get |
|---|---|
| `SELECT` (joins, subqueries, `UNION`) | Logical flow `FROM → JOIN → WHERE → GROUP BY → HAVING → SELECT → DISTINCT → ORDER BY → LIMIT`, `EXPLAIN` / `EXPLAIN FORMAT=JSON`, performance findings |
| `WITH` / `WITH RECURSIVE` | Each CTE explained: merged into the query or materialized, and why; recursion rules |
| Window functions | A `WINDOW` step (partition → sort → frame), extra-sort detection, `LAST_VALUE` / ranking pitfalls |
| `INSERT`, `REPLACE`, `UPDATE`, `DELETE` | Row lifecycle: locate → triggers → constraints → write → commit; missing `WHERE`, unsafe `LIMIT`, scans that lock rows, batch size |
| `CREATE VIEW` | `MERGE` vs `TEMPTABLE` (and what blocks merging), `CHECK OPTION`, `EXPLAIN` of the view body |
| `CREATE TRIGGER` | When it fires relative to constraints and writes, per-row cost, invalid `NEW`/`OLD`, writes to its own table, `BEFORE` vs `AFTER` advice |
| `CREATE INDEX` / `ALTER TABLE … ADD INDEX` | Build process, usable column prefixes, duplicate / redundant indexes (checked against your real schema), `TEXT` prefix, low selectivity |

The **Visual plan** card draws the plan as a top-to-bottom diagram (table access nodes colour-coded by access type, join
look-ups, temporary table, filesort, result) next to a **row funnel** showing estimated rows read vs rows kept per table.
All numbers are MySQL's estimates from `EXPLAIN`, not measured values.

Every finding has three parts: **what** MySQL is doing, **why it matters** (the cost), and **how to fix** it.

## The interface

* **Verdict first** – one card with the headline ("1 warning, 1 index suggested"), the biggest problem and the top fix.
  The detail sits in tabs: *Visual plan · Steps · Fixes · EXPLAIN · Notes*.
* **SQL editor** (CodeMirror) – syntax colors, line numbers, bracket matching, **Format SQL**, and autocomplete for
  keywords, your database's tables and columns, and tables from a pasted schema. `Ctrl/⌘ + Enter` analyzes.
* **Example cards** on the first screen – one click runs an example.
* **History and share links** – the last 10 queries are kept in your browser; **Share link** puts the query in the
  URL fragment (`#q=…`), which is never sent to a server. Opening such a link runs the analysis.
* **Light / dark / auto** theme switch.

The editor and the SQL formatter are loaded on demand (~140 KB and ~75 KB gzipped); the initial page is ~60 KB.

## Safety model

The statements you paste are **analysed, not executed**:

* Only `EXPLAIN` is ever sent to MySQL, and only for `SELECT`/DML text and for the body of a view.
  `CREATE TRIGGER`, `CREATE VIEW` and `CREATE INDEX` are parsed locally and never run.
* Stacked statements (`a; b`), executable comments (`/*! … */`), `SLEEP()`, `BENCHMARK()`,
  `LOAD_FILE()` and `INTO OUTFILE` are rejected before reaching the database.
* Schema lookups (`INFORMATION_SCHEMA`) use bind parameters.

Even so, run it with a **dedicated least-privilege MySQL user** (see `db/02-app-user.sql`).

## Quick start (Docker)

```bash
docker compose up --build
# open http://localhost:8080
```

This starts MySQL 8.4 with demo data (`customers`, `orders`, `order_audit`) and the app.

## Local development

Prerequisites: JDK 25, Node 22, a MySQL 8 instance.

```bash
# 1. database with demo data
mysql -u root -p < db/01-sample-data.sql

# 2. backend  (http://localhost:8080)
cd backend
export DB_URL='jdbc:mysql://localhost:3306/mysql_debugger?allowPublicKeyRetrieval=true&useSSL=false'
export DB_USERNAME=root DB_PASSWORD=root
./mvnw spring-boot:run

# 3. frontend (http://localhost:5173, proxies /api to :8080)
cd frontend
npm install
npm run dev
```

## Database tables panel

The top of the page lists every table in the connected database with its **row count** and column count
(`GET /api/tables`). Click a table to fill the editor with a `SELECT` on it. Counts are exact `COUNT(*)`
values for up to 50 tables, so point the app at a demo / non-huge database.

## "What if I add this index?"

Each suggested index has a **What if I add this index?** button. The app creates the index on a *temporary copy* (built from
your pasted schema, or a copy of the configured database for the demo tables), analyses the query again and shows
before / after: how each table is read, which index is used, estimated rows and cost, and whether the extra sort or temporary
table disappears. Your real database is never changed (`POST /api/whatif`). The copy of the configured database is limited
to 20 tables and about 500,000 rows. With tiny tables MySQL may ignore the index, and the result then says "No change".

## Analyse your own tables

Open **Use your own tables** under the editor and paste your `CREATE TABLE` / `CREATE INDEX` statements
(e.g. the output of `SHOW CREATE TABLE`). The query is then analysed against an **empty temporary copy** of
that schema, which is dropped right afterwards. Nothing connects to your real database.

Click **Load & check tables** to build the temporary tables and see them with their row counts (`POST /api/schema/check`).
The temporary tables are re-created on every Analyze. While the schema box has content, **only your tables exist**: the
demo tables (`customers`, ...) are not available, so write the query against your own tables.

You can also paste `INSERT INTO ... VALUES (...)` statements to load **sample rows** into the temporary tables
(`ANALYZE TABLE` is run afterwards so MySQL's statistics reflect them). The rows exist only for that one analysis.

* Real: access types (full scan vs index), which index MySQL picks, filesort / temp-table steps, index suggestions.
* Estimates (row counts, costs) are only as realistic as the rows you paste. With a handful of rows MySQL often prefers
  a full scan even when an index exists, because that is cheaper for tiny tables.
* Only `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE` and `INSERT INTO` are accepted; `SELECT` (so no `INSERT ... SELECT`),
  `db.table` names, file options and similar are rejected. Limits: 100,000 characters, 200 statements,
  4 sandboxes at a time.
* The MySQL user needs `CREATE`/`DROP` on schemas named `sbx_%` (see `db/02-app-user.sql`). `root` already has it.

## API

```http
POST /api/analyze
Content-Type: application/json

{"query": "UPDATE orders SET status = 'ARCHIVED'"}
```

Response (abridged):

```json
{
  "statementType": "UPDATE",
  "summary": "UPDATE changes every row of 'orders'.",
  "executionFlow": [{ "step": 1, "operation": "TARGET", "description": "…", "expression": "orders", "details": "" }],
  "executionPlan": [{ "table": "orders", "type": "ALL", "rows": 19876 }],
  "jsonExecutionPlan": { "queryCost": 2012.5, "orderingUsingFilesort": false },
  "performanceIssues": [{
    "severity": "WARNING",
    "title": "UPDATE Without WHERE",
    "description": "The statement affects every row of the table.",
    "reason": "It touches and locks all rows, produces a huge undo log …",
    "suggestion": "Add a WHERE clause, or update in batches …"
  }],
  "indexSuggestions": [{ "table": "orders", "columns": ["status"], "ddl": "CREATE INDEX …;", "reason": "…" }],
  "notes": ["…"]
}
```

Errors are `{"error": "INVALID_QUERY" | "DATABASE_ERROR" | …, "message": "…"}` with HTTP 400.
`POST /api/debug` is kept as an alias. `GET /api/tables` and `GET /api/columns` feed the tables panel and editor autocomplete.

## Project layout

```
backend/    Spring Boot 4 / Java 25 – validator, classifier, per-statement explainers, index suggester
frontend/   React 18 + Vite – editor, execution stepper, findings, EXPLAIN table
db/         demo schema + data, least-privilege app user
Dockerfile  builds the React app and bundles it into the Spring Boot jar
```

How the backend is organised: `QueryValidator` → `StatementClassifier` → one explainer per statement type
(`SelectExplainer`, `DmlExplainer`, `ViewExplainer`, `TriggerExplainer`, `IndexExplainer`) → `EXPLAIN` →
`PerformanceAnalyzer` + `IndexSuggester`, orchestrated by `AnalysisService`.

## Tests

```bash
cd backend && mvn test      # unit tests need no database; the context-load test needs MySQL
cd frontend && npm test && npm run build   # 50 component/logic tests (vitest + jsdom)
```

## Deploying

See [DEPLOY.md](DEPLOY.md).

## Limitations

* Explanations come from parsing the SQL text plus MySQL's `EXPLAIN`; they are guidance, not a substitute for
  measuring on production-sized data. Optimizer behaviour differs between MySQL versions.
* Index suggestions follow the usual equality → sort → range rule and don't use column cardinality yet.
* For `UNION`, the step-by-step flow covers the first `SELECT` (the rest is summarised).
* Triggers and indexes are explained from their definition; MySQL cannot `EXPLAIN` objects that don't exist yet.

## License

MIT
