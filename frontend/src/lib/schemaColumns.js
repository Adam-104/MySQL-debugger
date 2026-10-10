// Table → column names from pasted CREATE TABLE statements, so autocomplete also works for the user's own tables.

const NOT_COLUMNS = /^(PRIMARY|KEY|INDEX|UNIQUE|CONSTRAINT|FOREIGN|FULLTEXT|SPATIAL|CHECK)\b/i;

function matchingParen(text, open) {
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    const c = text[i];
    if (c === "'" || c === '"') {
      const q = c;
      i++;
      while (i < text.length && text[i] !== q) {
        if (text[i] === '\\') i++;
        i++;
      }
    } else if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return i;
  }
  return -1;
}

function splitTopLevel(body) {
  const parts = [];
  let depth = 0;
  let last = 0;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === "'" || c === '"') {
      const q = c;
      i++;
      while (i < body.length && body[i] !== q) {
        if (body[i] === '\\') i++;
        i++;
      }
    } else if (c === '(') depth++;
    else if (c === ')') depth--;
    else if (c === ',' && depth === 0) {
      parts.push(body.slice(last, i));
      last = i + 1;
    }
  }
  parts.push(body.slice(last));
  return parts.map((p) => p.trim()).filter(Boolean);
}

/** @returns {Record<string,string[]>} */
export function parseSchemaColumns(schemaText) {
  const result = {};
  if (!schemaText) return result;
  const re = /CREATE\s+(?:TEMPORARY\s+)?TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(/gi;
  let m;
  while ((m = re.exec(schemaText))) {
    const open = m.index + m[0].length - 1;
    const close = matchingParen(schemaText, open);
    if (close < 0) continue;
    const columns = [];
    for (const def of splitTopLevel(schemaText.slice(open + 1, close))) {
      if (NOT_COLUMNS.test(def)) continue;
      const name = /^`?(\w+)`?/.exec(def);
      if (name) columns.push(name[1]);
    }
    if (columns.length) result[m[1]] = columns;
    re.lastIndex = close;
  }
  return result;
}

/**
 * Autocomplete schema for CodeMirror's SQL language: table name → column names.
 * Pasted-schema tables win over the connected database's tables of the same name.
 */
export function buildCompletionSchema(tables = [], columnsByTable = {}, pasted = {}) {
  const schema = {};
  for (const t of tables) schema[t.name] = columnsByTable[t.name] || [];
  for (const [name, cols] of Object.entries(columnsByTable)) schema[name] = cols;
  for (const [name, cols] of Object.entries(pasted)) schema[name] = cols;
  return schema;
}
