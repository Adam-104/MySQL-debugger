// Last queries, kept in the browser (localStorage). Every storage call is guarded: storage can be missing
// (private mode), full, or hold garbage written by something else.

export const HISTORY_KEY = 'mysql-debugger:history:v1';
export const MAX_HISTORY = 10;

function store(storage) {
  try {
    return storage ?? globalThis.localStorage ?? null;
  } catch {
    return null;
  }
}

export function loadHistory(storage) {
  const s = store(storage);
  if (!s) return [];
  try {
    const parsed = JSON.parse(s.getItem(HISTORY_KEY) || '[]');
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((e) => e && typeof e.sql === 'string' && e.sql.trim() && typeof e.savedAt === 'number')
      .slice(0, MAX_HISTORY);
  } catch {
    return [];
  }
}

function write(list, storage) {
  const s = store(storage);
  if (!s) return;
  try {
    s.setItem(HISTORY_KEY, JSON.stringify(list));
  } catch {
    /* quota exceeded or storage disabled: history is a convenience, ignore */
  }
}

/** Adds an entry on top; the same query + schema moves to the top instead of duplicating. Returns the new list. */
export function addToHistory({ sql, schema = '', type = '' }, storage, now = Date.now()) {
  const clean = sql.trim();
  if (!clean) return loadHistory(storage);
  const schemaText = schema.trim();
  const rest = loadHistory(storage).filter((e) => !(e.sql.trim() === clean && (e.schema || '').trim() === schemaText));
  const list = [{ id: `${now}-${Math.random().toString(36).slice(2, 7)}`, sql: clean, schema: schemaText, type, savedAt: now }, ...rest].slice(
    0,
    MAX_HISTORY
  );
  write(list, storage);
  return list;
}

export function removeFromHistory(id, storage) {
  const list = loadHistory(storage).filter((e) => e.id !== id);
  write(list, storage);
  return list;
}

export function clearHistory(storage) {
  write([], storage);
  return [];
}

/** First meaningful line of the SQL, shortened for a list. */
export function previewSql(sql, max = 70) {
  const line = (sql || '').split('\n').map((l) => l.trim()).find((l) => l && !l.startsWith('--')) || '';
  const flat = line.replace(/\s+/g, ' ');
  return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat;
}

export function timeAgo(ts, now = Date.now()) {
  const sec = Math.max(0, Math.round((now - ts) / 1000));
  if (sec < 45) return 'just now';
  const min = Math.round(sec / 60);
  if (min < 60) return `${min} min ago`;
  const hours = Math.round(min / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return `${days} d ago`;
}
