# Deploying

The app is a single container (Spring Boot serving the built React UI) plus a MySQL 8 database it can reach.

## Required configuration

| Variable | Meaning | Default |
|---|---|---|
| `DB_URL` | JDBC URL, e.g. `jdbc:mysql://host:3306/mydb?allowPublicKeyRetrieval=true&useSSL=false` (use `useSSL=true` for managed databases) | `jdbc:mysql://localhost:3306/mysql_debugger` |
| `DB_USERNAME` | MySQL user | `root` |
| `DB_PASSWORD` | MySQL password | `root` |
| `PORT` / `SERVER_PORT` | HTTP port (set `SERVER_PORT` to match the platform's `PORT`) | `8080` |

## Option A – any VPS with Docker

```bash
git clone <your-repo-url> && cd mysql-debugger
docker compose up -d --build
```

Put a reverse proxy with HTTPS in front (Caddy or nginx) and expose only 443.
Change the passwords in `docker-compose.yml` / `db/02-app-user.sql` first.

## Option B – a container platform (Railway, Render, Fly.io, …)

1. Create a MySQL 8 database on the platform (or any managed MySQL).
2. Load `db/01-sample-data.sql` if you want the demo tables, and create a limited user like in `db/02-app-user.sql`.
3. Create a service from this repository using the root `Dockerfile`.
4. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (and `SERVER_PORT` if the platform injects `PORT`).

## "Use your own tables" mode (temporary schemas)

Each request that includes a schema creates a schema named `sbx_<random>` and drops it afterwards. The database user
therefore needs `GRANT ALL PRIVILEGES ON \`sbx\_%\`.* TO 'debugger'@'%';` (already in `db/02-app-user.sql`). If your
database was created before this change, run that GRANT once by hand (the init scripts only run on a fresh volume).
"What if I add this index?" on the demo tables copies the configured database (structure and rows, max 20 tables / about 500,000 rows) into the same kind of temporary schema, so the same `sbx_%` privilege plus `SELECT` on the real schema is enough.
If the server is killed mid-request a leftover `sbx_...` schema can remain; list them with
`SHOW DATABASES LIKE 'sbx\_%';` and drop them.

## Before exposing it publicly

The app analyses SQL that visitors type. It never executes it, but:

* **Use a dedicated database** that contains only demo / non-sensitive data. `EXPLAIN` output and
  `INFORMATION_SCHEMA` lookups reveal table and column names to anyone who can use the app.
* **Use the least-privilege user** from `db/02-app-user.sql`, never `root`.
* **Add authentication and rate limiting** (reverse proxy, or Spring Security) if the app is on the open internet;
  there is none built in.
* Serve over **HTTPS**.

## Step by step: GitHub + a host

### 1. Push to GitHub

```bash
cd mysql-debugger-repo/repo
git add .
git commit -m "Add table row counts, responsive UI"
git branch -M main
git remote add origin https://github.com/<your-username>/mysql-debugger.git   # create the empty repo on github.com first
git push -u origin main
```

The CI workflow in `.github/workflows/ci.yml` then runs the tests and the Docker build on every push.

### 2. Get a hosted MySQL (your laptop's MySQL is not reachable from the internet)

Use any managed MySQL 8 (Railway, Aiven, TiDB Cloud, PlanetScale-compatible hosts, a VPS…). Free tiers change, so check
current limits. Note the host, port, database name, user and password.

Load the demo data into it. If your hosted database is not named `mysql_debugger`, remove the first
`CREATE DATABASE` / `USE` lines from `db/01-sample-data.sql` and run it against your database instead.

### 3. Deploy the app (Docker)

On Render / Railway / Fly.io: *New → Web Service → connect the GitHub repo → runtime Docker* (it uses the root `Dockerfile`).
Set these environment variables:

| Variable | Example |
|---|---|
| `DB_URL` | `jdbc:mysql://<host>:<port>/<database>?useSSL=true` |
| `DB_USERNAME` | the limited user |
| `DB_PASSWORD` | its password |

`PORT` is read automatically. After the deploy finishes, open the service URL: the UI and the API are served by the same app.

Give the service at least 512 MB RAM (it is a JVM app); free instances may sleep when idle and start slowly.
