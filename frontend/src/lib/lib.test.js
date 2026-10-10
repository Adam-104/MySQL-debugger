import { describe, expect, it, beforeEach } from 'vitest';
import { buildVerdict } from './verdict.js';
import {
  addToHistory, clearHistory, loadHistory, removeFromHistory, previewSql, timeAgo, HISTORY_KEY, MAX_HISTORY,
} from './history.js';
import { buildShareUrl, decodeShare, encodeShare, MAX_SHARE_URL_LENGTH } from './share.js';
import { applyTheme, getStoredTheme, storeTheme, THEME_KEY } from './theme.js';
import { formatSql } from './format.js';
import { buildCompletionSchema, parseSchemaColumns } from './schemaColumns.js';

function memoryStorage(initial = {}) {
  const data = { ...initial };
  return {
    data,
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => { data[k] = String(v); },
  };
}

describe('buildVerdict', () => {
  const scan = { severity: 'WARNING', title: 'Full Table Scan', table: 'orders', estimatedRows: 19876, suggestion: 'Add an index.' };
  const sort = { severity: 'NOTICE', title: 'Filesort Detected', suggestion: 'Index the ORDER BY.' };

  it('summarises counts and picks the first issue as the biggest problem', () => {
    const v = buildVerdict({
      performanceIssues: [scan, sort, { severity: 'INFO', title: 'SELECT * Used' }],
      indexSuggestions: [{ ddl: 'CREATE INDEX i ON orders (status);', reason: 'why' }],
    });
    expect(v.tone).toBe('bad');
    expect(v.headline).toBe('1 warning, 1 notice, 1 tip, 1 index suggested');
    expect(v.biggest.text).toBe('orders is read with a full table scan (about 19,876 rows).');
    expect(v.topFix).toEqual({ kind: 'index', ddl: 'CREATE INDEX i ON orders (status);', text: 'why' });
  });

  it('uses the issue suggestion when the top problem is not index related', () => {
    const v = buildVerdict({
      performanceIssues: [{ severity: 'WARNING', title: 'UPDATE Without WHERE', description: 'Touches every row.', suggestion: 'Add a WHERE clause.' }],
      indexSuggestions: [{ ddl: 'CREATE INDEX x ON t (a);', reason: 'r' }],
    });
    expect(v.biggest.text).toBe('Touches every row.');
    expect(v.topFix).toEqual({ kind: 'text', text: 'Add a WHERE clause.' });
  });

  it('is green when nothing was found and tolerates missing fields', () => {
    expect(buildVerdict({}).tone).toBe('good');
    expect(buildVerdict({}).headline).toBe('No problems found');
    expect(buildVerdict(null).topFix).toBeNull();
  });

  it('pluralises correctly', () => {
    const v = buildVerdict({ performanceIssues: [scan, scan], indexSuggestions: [{ ddl: 'a', reason: 'b' }, { ddl: 'c', reason: 'd' }] });
    expect(v.headline).toBe('2 warnings, 2 indexes suggested');
  });
});

describe('history', () => {
  let s;
  beforeEach(() => { s = memoryStorage(); });

  it('keeps newest first, de-duplicates, and caps the list', () => {
    addToHistory({ sql: 'SELECT 1' }, s, 1000);
    addToHistory({ sql: 'SELECT 2' }, s, 2000);
    addToHistory({ sql: '  SELECT 1  ' }, s, 3000);
    expect(loadHistory(s).map((e) => e.sql)).toEqual(['SELECT 1', 'SELECT 2']);

    for (let i = 0; i < 15; i++) addToHistory({ sql: `SELECT ${100 + i}` }, s, 4000 + i);
    expect(loadHistory(s)).toHaveLength(MAX_HISTORY);
    expect(loadHistory(s)[0].sql).toBe('SELECT 114');
  });

  it('treats the same SQL with a different schema as a different entry', () => {
    addToHistory({ sql: 'SELECT 1', schema: 'CREATE TABLE a (id INT)' }, s);
    addToHistory({ sql: 'SELECT 1', schema: '' }, s);
    expect(loadHistory(s)).toHaveLength(2);
  });

  it('removes and clears', () => {
    const [first] = addToHistory({ sql: 'SELECT 1' }, s);
    addToHistory({ sql: 'SELECT 2' }, s);
    expect(removeFromHistory(first.id, s).map((e) => e.sql)).toEqual(['SELECT 2']);
    expect(clearHistory(s)).toEqual([]);
    expect(loadHistory(s)).toEqual([]);
  });

  it('survives corrupted, malformed or unavailable storage', () => {
    expect(loadHistory(memoryStorage({ [HISTORY_KEY]: '{not json' }))).toEqual([]);
    expect(loadHistory(memoryStorage({ [HISTORY_KEY]: '{"a":1}' }))).toEqual([]);
    expect(loadHistory(memoryStorage({ [HISTORY_KEY]: JSON.stringify([{ sql: 5 }, null, { sql: 'ok', savedAt: 1 }]) }))).toHaveLength(1);
    const full = { getItem: () => null, setItem: () => { throw new Error('quota'); } };
    expect(() => addToHistory({ sql: 'SELECT 1' }, full)).not.toThrow();
    const broken = { getItem: () => { throw new Error('denied'); }, setItem: () => {} };
    expect(loadHistory(broken)).toEqual([]);
  });

  it('ignores empty SQL', () => {
    expect(addToHistory({ sql: '   ' }, s)).toEqual([]);
  });

  it('previews and times', () => {
    expect(previewSql('-- note\n\nSELECT   *\nFROM t')).toBe('SELECT *');
    expect(previewSql('x'.repeat(200), 20)).toHaveLength(20);
    expect(timeAgo(1000, 1000 + 10_000)).toBe('just now');
    expect(timeAgo(0, 5 * 60_000)).toBe('5 min ago');
    expect(timeAgo(0, 3 * 3_600_000)).toBe('3 h ago');
    expect(timeAgo(0, 2 * 86_400_000)).toBe('2 d ago');
  });
});

describe('share links', () => {
  it('round-trips SQL with unicode, quotes and newlines', () => {
    const sql = "SELECT 'héllo — 🐬', \"x\"\nFROM `t` WHERE a = '%'";
    const { url } = buildShareUrl({ origin: 'https://x.test', pathname: '/app/' }, { sql, schema: 'CREATE TABLE t (a INT);' });
    expect(url.startsWith('https://x.test/app/#q=')).toBe(true);
    expect(url.slice(url.indexOf('#q=') + 3)).toMatch(/^[A-Za-z0-9_-]+$/); // url-safe alphabet, no padding
    expect(decodeShare(url.slice(url.indexOf('#')))).toEqual({ sql, schema: 'CREATE TABLE t (a INT);' });
  });

  it('omits an empty schema', () => {
    expect(decodeShare(`#q=${encodeShare({ sql: 'SELECT 1' })}`)).toEqual({ sql: 'SELECT 1', schema: '' });
  });

  it('rejects garbage instead of throwing', () => {
    for (const bad of ['', '#', '#q=', '#q=%%%', '#q=!!!!', `#q=${btoa('{"v":2,"s":"x"}')}`, `#q=${btoa('[1]')}`, '#other=1']) {
      expect(decodeShare(bad)).toBeNull();
    }
    expect(decodeShare(`#q=${encodeShare({ sql: '   ' })}`)).toBeNull();
    expect(decodeShare(undefined)).toBeNull();
  });

  it('flags links that are too long to share', () => {
    const r = buildShareUrl({ origin: 'https://x.test', pathname: '/' }, { sql: 'SELECT ' + 'x, '.repeat(5000) });
    expect(r.tooLong).toBe(true);
    expect(r.url.length).toBeGreaterThan(MAX_SHARE_URL_LENGTH);
  });
});

describe('theme', () => {
  it('stores, validates and applies', () => {
    const s = memoryStorage();
    expect(getStoredTheme(s)).toBe('system');
    storeTheme('dark', s);
    expect(s.data[THEME_KEY]).toBe('dark');
    expect(getStoredTheme(s)).toBe('dark');
    expect(getStoredTheme(memoryStorage({ [THEME_KEY]: 'neon' }))).toBe('system');

    const root = document.createElement('html');
    applyTheme('light', root);
    expect(root.getAttribute('data-theme')).toBe('light');
    applyTheme('system', root);
    expect(root.hasAttribute('data-theme')).toBe(false);
  });
});

describe('formatSql', () => {
  it('formats MySQL with upper-case keywords', async () => {
    const out = await formatSql("select a,b from t where x=1 and y='z' order by a limit 5");
    expect(out).toContain('SELECT');
    expect(out).toContain('\nFROM');
    expect(out.split('\n').length).toBeGreaterThan(3);
  });
  it('refuses DELIMITER scripts with a clear message', async () => {
    await expect(formatSql('DELIMITER $$\nCREATE TRIGGER t ...')).rejects.toThrow(/DELIMITER/);
  });
});

describe('schema columns', () => {
  const ddl = `CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(150) NOT NULL DEFAULT 'a,b',
    price DECIMAL(10,2),
    \`created_at\` DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uq_email (email),
    CONSTRAINT fk FOREIGN KEY (id) REFERENCES other(id)
  );
  CREATE TABLE IF NOT EXISTS invoices (id INT, user_id INT, INDEX idx_user (user_id));
  INSERT INTO users (email) VALUES ('x');`;

  it('extracts column names, skipping keys and constraints', () => {
    expect(parseSchemaColumns(ddl)).toEqual({
      users: ['id', 'email', 'price', 'created_at'],
      invoices: ['id', 'user_id'],
    });
  });
  it('handles empty / broken input', () => {
    expect(parseSchemaColumns('')).toEqual({});
    expect(parseSchemaColumns('CREATE TABLE t (id INT')).toEqual({});
  });
  it('merges database tables, database columns and pasted tables', () => {
    expect(buildCompletionSchema([{ name: 'a' }, { name: 'b' }], { a: ['x'] }, { c: ['y'], a: ['z'] })).toEqual({
      a: ['z'], b: [], c: ['y'],
    });
  });
});
