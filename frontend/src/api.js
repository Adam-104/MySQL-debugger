// All calls to the Spring Boot backend.
//
// - Local dev and the single-container Docker image: the backend is same-origin, so API_BASE is ''.
// - Vercel (frontend) + Render (backend): set VITE_API_BASE_URL to the Render URL at build time.
// - A free Render service sleeps when idle and needs up to ~60 s to wake up, so every call
//   has a long timeout, retries on "server not ready" answers, and reports when it is waiting.

const RAW_BASE = (import.meta.env && import.meta.env.VITE_API_BASE_URL) || '';
export const API_BASE = RAW_BASE.replace(/\/+$/, '');

const TIMEOUT_MS = 70_000; // covers a cold start
const RETRY_STATUS = new Set([502, 503, 504]); // what Render's proxy answers while the app boots
const RETRY_DELAYS = [1500, 3500, 6000];

const listeners = new Set();
let waiting = 0;

/** Subscribe to "the server is waking up" changes. The callback gets true/false. Returns an unsubscribe function. */
export function onWaking(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

function setWaking(delta) {
  const before = waiting > 0;
  waiting = Math.max(0, waiting + delta);
  const after = waiting > 0;
  if (before !== after) listeners.forEach((fn) => fn(after));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * fetch with a timeout and retries. Only network failures and 502/503/504 are retried; the analysis
 * endpoints only run EXPLAIN, so repeating a request is always safe.
 */
async function request(path, options = {}, { retries = RETRY_DELAYS.length } = {}) {
  let lastError;
  for (let attempt = 0; attempt <= retries; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    // only announce "waking up" if the call is slow or has already failed once
    let announced = false;
    const slowTimer = setTimeout(() => {
      announced = true;
      setWaking(+1);
    }, attempt === 0 ? 2500 : 0);
    try {
      const res = await fetch(API_BASE + path, { ...options, signal: controller.signal });
      if (RETRY_STATUS.has(res.status) && attempt < retries) {
        lastError = new Error('The server is starting up.');
      } else {
        return res;
      }
    } catch (e) {
      lastError = e;
      if (attempt >= retries) break;
    } finally {
      clearTimeout(timer);
      clearTimeout(slowTimer);
      if (announced) setWaking(-1);
    }
    await sleep(RETRY_DELAYS[attempt] ?? 6000);
  }
  throw lastError || new Error('Request failed');
}

const NO_BACKEND =
  'Cannot reach the backend. If it is on a free plan it may still be waking up, so try again in a minute.';

const json = (body) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

async function readJson(res) {
  return res.json().catch(() => null);
}

/** Sends SQL to the backend and returns the analysis. Throws Error(message) on failure. */
export async function analyze(query, schema = '') {
  let res;
  try {
    res = await request('/api/analyze', json({ query, schema: schema.trim() ? schema : undefined }));
  } catch {
    throw new Error(NO_BACKEND);
  }
  const data = (await readJson(res)) || {};
  if (!res.ok) throw new Error(data.message || `Request failed (${res.status})`);
  return data;
}

/** Tables of the connected database with row counts. Throws Error(message) on failure. */
export async function fetchTables() {
  let res;
  try {
    res = await request('/api/tables');
  } catch {
    throw new Error(NO_BACKEND);
  }
  const data = await readJson(res);
  if (!res.ok || !Array.isArray(data)) {
    throw new Error((data && data.message) || `Request failed (${res.status})`);
  }
  return data;
}

/** Column names per table of the connected database (for editor autocomplete). Never throws: returns {} on failure. */
export async function fetchColumns() {
  try {
    const res = await request('/api/columns', {}, { retries: 1 });
    const data = await readJson(res);
    return res.ok && data && typeof data === 'object' && !Array.isArray(data) ? data : {};
  } catch {
    return {};
  }
}

/** Loads the pasted schema into a temporary database and returns its tables with row counts. */
export async function checkSchema(schema) {
  let res;
  try {
    res = await request('/api/schema/check', json({ schema }));
  } catch {
    throw new Error(NO_BACKEND);
  }
  const data = await readJson(res);
  if (!res.ok || !Array.isArray(data)) {
    throw new Error((data && data.message) || `Request failed (${res.status})`);
  }
  return data;
}

/** Compares the query's plan before and after adding one index (on a temporary copy). */
export async function whatIf(query, schema, indexDdl) {
  let res;
  try {
    res = await request('/api/whatif', json({ query, schema: schema && schema.trim() ? schema : undefined, indexDdl }));
  } catch {
    throw new Error(NO_BACKEND);
  }
  const data = await readJson(res);
  if (!res.ok || !data || !data.before) {
    throw new Error((data && data.message) || `Request failed (${res.status})`);
  }
  return data;
}

/** Fire-and-forget ping that wakes a sleeping backend while the visitor reads the page. */
export function warmUp() {
  fetch(API_BASE + '/api/health', { cache: 'no-store' }).catch(() => {});
}
