# Deploying

There are two ways to run the app. Both use the same code.

| | Layout | Good for |
|---|---|---|
| **A. Split (recommended)** | Frontend on **Vercel** (global CDN, instant page load) + backend on **Render** + MySQL on **Aiven** | Fast first load, free |
| **B. Single container** | One Docker service serves the UI and the API | VPS, `docker compose`, or a single Render service |

Never put passwords in this repository or in chat. Set them only in the hosting dashboards.

---

## A. Vercel (frontend) + Render (backend) + Aiven (MySQL)

```
Browser ──► Vercel (static React files, CDN)
   │
   └──────► Render (Spring Boot API) ──► Aiven (MySQL)
```

The browser loads the page from Vercel in a fraction of a second, then calls the Render API directly.
Render's free service sleeps when idle, so the page sends a small wake-up request the moment it opens and shows a
"Waking up the server" notice if the first answer is slow.

### Step 1: backend on Render

If the backend is already running on Render from the earlier setup, keep it and only add one variable (below).

New service: *New → Web Service → connect the GitHub repo → Language: Docker*. Use the root `Dockerfile`, or
`Dockerfile.api` for a faster, smaller backend-only build (set *Dockerfile Path* to `./Dockerfile.api`).

Environment variables:

| Variable | Value |
|---|---|
| `DB_URL` | `jdbc:mysql://<host>:<port>/mysql_debugger?sslMode=REQUIRED` |
| `DB_USERNAME` | the limited database user |
| `DB_PASSWORD` | its password |
| `APP_SANDBOX_SCHEMAS` | only for hosts without wildcard grants such as Aiven: `sbxslot1,sbxslot2,sbxslot3,sbxslot4` |
| `APP_CORS_ORIGINS` | your Vercel address, for example `https://your-app.vercel.app` (comma-separate several; `*` is allowed as a wildcard, for example `https://your-app-*.vercel.app` for preview deployments) |

Without `APP_CORS_ORIGINS` the browser blocks the calls from Vercel.

After deploying, open `https://<your-service>.onrender.com/api/health`. It should answer `{"status":"ok","db":true}`.

### Step 2: frontend on Vercel

1. vercel.com → *Add New → Project* → import the same GitHub repository.
2. **Root Directory:** `frontend`. Vercel detects Vite; `frontend/vercel.json` already sets build command, output folder and cache headers.
3. **Environment Variables:** `VITE_API_BASE_URL` = `https://<your-service>.onrender.com` (no trailing slash).
4. *Deploy*.
5. Copy the Vercel address into `APP_CORS_ORIGINS` on Render (Step 1) and let Render redeploy.

`VITE_API_BASE_URL` is baked in at build time. If you change it, redeploy the Vercel project.

### Step 3 (optional): keep the backend awake

A free Render service sleeps after about 15 minutes without traffic and needs up to a minute to start again.
A free uptime monitor (for example UptimeRobot) that requests `https://<your-service>.onrender.com/api/health` every 5 minutes keeps it awake,
and also keeps a free Aiven database from idling because the health check runs `SELECT 1`.
One always-on free web service fits inside Render's monthly free hours; check Render's current free-tier terms.

### Why the page is fast

* Initial download is about 56 KB gzipped (React plus the app). The code editor, the results view and the SQL formatter load only when needed, and the results code is fetched while the browser is idle.
* Vercel serves the files from a CDN with long-lived caching for hashed assets.
* The browser opens the connection to the API host early (`preconnect`) and pings `/api/health` immediately.
* API responses are compressed, and failed or slow requests retry automatically while the backend starts.

---

## B. Single container

```bash
git clone <your-repo-url> && cd mysql-debugger
docker compose up -d --build
```

Put a reverse proxy with HTTPS in front (Caddy or nginx) and expose only 443.
On a platform such as Render use the root `Dockerfile`; the UI and API are then the same origin, so `VITE_API_BASE_URL`
and `APP_CORS_ORIGINS` are not needed.

---

## Configuration reference (backend)

| Variable | Meaning | Default |
|---|---|---|
| `DB_URL` | JDBC URL (`sslMode=REQUIRED` for managed databases) | `jdbc:mysql://localhost:3306/mysql_debugger` |
| `DB_USERNAME` | MySQL user | `root` |
| `DB_PASSWORD` | MySQL password | `root` (local only) |
| `PORT` / `SERVER_PORT` | HTTP port; hosts set `PORT` | `8080` |
| `APP_CORS_ORIGINS` | Allowed frontend origins, comma separated, `*` wildcard allowed | empty (same-origin only) |
| `APP_SANDBOX_SCHEMAS` | Fixed names of temporary schemas (see below) | empty (random `sbx_<id>` names) |
| `JAVA_OPTS` | JVM options | `-XX:MaxRAMPercentage=75` |

Frontend build variable: `VITE_API_BASE_URL` (see above).

## "Use your own tables" mode (temporary schemas)

Each request that includes a schema creates a temporary schema and drops it afterwards.

* **Hosts that allow wildcard grants** (local MySQL, docker compose): the user needs
  ``GRANT ALL PRIVILEGES ON `sbx\_%`.* TO 'debugger'@'%';`` (already in `db/02-app-user.sql`). Schemas are named `sbx_<random>`.
* **Hosts with `partial_revokes` on (Aiven)**: wildcard grants do not take effect. Create four fixed schemas, grant the user
  access to exactly those, and set `APP_SANDBOX_SCHEMAS`:

  ```sql
  CREATE DATABASE sbxslot1; CREATE DATABASE sbxslot2; CREATE DATABASE sbxslot3; CREATE DATABASE sbxslot4;
  GRANT ALL PRIVILEGES ON sbxslot1.* TO 'debugger'@'%';
  GRANT ALL PRIVILEGES ON sbxslot2.* TO 'debugger'@'%';
  GRANT ALL PRIVILEGES ON sbxslot3.* TO 'debugger'@'%';
  GRANT ALL PRIVILEGES ON sbxslot4.* TO 'debugger'@'%';
  ```

  At most four sandboxes run at once; extra requests get a "busy, try again" message.

"What if I add this index?" on the demo tables copies the configured database (max 20 tables / about 500,000 rows)
into one of these temporary schemas. If the server is killed mid-request a leftover schema can remain; list them with
``SHOW DATABASES LIKE 'sbx%';`` and clean them up.

## Before exposing it publicly

The app analyses SQL that visitors type. It never executes it, but:

* **Use a dedicated database** that contains only demo data. `EXPLAIN` output and `INFORMATION_SCHEMA` lookups reveal table and column names to anyone who can use the app.
* **Use the least-privilege user** from `db/02-app-user.sql` (change its password first), never `root` / `avnadmin`.
* **Add authentication and rate limiting** if the app is on the open internet; none is built in.
* Serve over **HTTPS** (Vercel and Render do this for you).
* If a password was ever pasted into a chat, an issue or a commit, rotate it.

## Push to GitHub

```bash
git add .
git commit -m "Vercel + Render split, faster loading, typewriter header"
git push
```

Vercel and Render both redeploy automatically on every push. The CI workflow (`.github/workflows/ci.yml`) runs the backend and frontend tests.
