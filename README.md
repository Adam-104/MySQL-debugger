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

Every finding has three parts: **what** MySQL is doing, **why it matters** (the cost), and **how to fix** it.

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
`POST /api/debug` is kept as an alias.

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
cd frontend && npm run build
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
