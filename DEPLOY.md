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

## Before exposing it publicly

The app analyses SQL that visitors type. It never executes it, but:

* **Use a dedicated database** that contains only demo / non-sensitive data. `EXPLAIN` output and
  `INFORMATION_SCHEMA` lookups reveal table and column names to anyone who can use the app.
* **Use the least-privilege user** from `db/02-app-user.sql`, never `root`.
* **Add authentication and rate limiting** (reverse proxy, or Spring Security) if the app is on the open internet;
  there is none built in.
* Serve over **HTTPS**.
