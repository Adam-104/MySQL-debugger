/** Sends SQL to the backend and returns the analysis. Throws Error(message) on failure. */
export async function analyze(query, schema = '') {
  let res;
  try {
    res = await fetch('/api/analyze', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, schema: schema.trim() ? schema : undefined }),
    });
  } catch {
    throw new Error('Cannot reach the backend. Is the Spring Boot server running?');
  }

  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.message || `Request failed (${res.status})`);
  }
  return data;
}

/** Tables of the connected database with row counts. Throws Error(message) on failure. */
export async function fetchTables() {
  let res;
  try {
    res = await fetch('/api/tables');
  } catch {
    throw new Error('Cannot reach the backend.');
  }
  const data = await res.json().catch(() => null);
  if (!res.ok || !Array.isArray(data)) {
    throw new Error((data && data.message) || `Request failed (${res.status})`);
  }
  return data;
}

/** Column names per table of the connected database (for editor autocomplete). Never throws: returns {} on failure. */
export async function fetchColumns() {
  try {
    const res = await fetch('/api/columns');
    const data = await res.json().catch(() => null);
    return res.ok && data && typeof data === 'object' && !Array.isArray(data) ? data : {};
  } catch {
    return {};
  }
}

/** Loads the pasted schema into a temporary database and returns its tables with row counts. */
export async function checkSchema(schema) {
  let res;
  try {
    res = await fetch('/api/schema/check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ schema }),
    });
  } catch {
    throw new Error('Cannot reach the backend. Is the Spring Boot server running?');
  }
  const data = await res.json().catch(() => null);
  if (!res.ok || !Array.isArray(data)) {
    throw new Error((data && data.message) || `Request failed (${res.status})`);
  }
  return data;
}

/** Compares the query's plan before and after adding one index (on a temporary copy). */
export async function whatIf(query, schema, indexDdl) {
  let res;
  try {
    res = await fetch('/api/whatif', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query, schema: schema && schema.trim() ? schema : undefined, indexDdl }),
    });
  } catch {
    throw new Error('Cannot reach the backend. Is the Spring Boot server running?');
  }
  const data = await res.json().catch(() => null);
  if (!res.ok || !data || !data.before) {
    throw new Error((data && data.message) || `Request failed (${res.status})`);
  }
  return data;
}
