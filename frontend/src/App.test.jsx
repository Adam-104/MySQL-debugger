import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';

// A plain textarea stands in for CodeMirror here (the real editor has its own test).
vi.mock('./components/SqlEditor.jsx', () => ({
  default: ({ value, onChange, onRun, ariaLabel }) => (
    <textarea
      aria-label={ariaLabel}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') onRun?.();
      }}
    />
  ),
}));

vi.mock('./api.js', () => ({
  analyze: vi.fn(),
  fetchTables: vi.fn(),
  fetchColumns: vi.fn(),
  checkSchema: vi.fn(),
  whatIf: vi.fn(),
  warmUp: vi.fn(),
  onWaking: () => () => {},
}));

import App from './App.jsx';
import * as api from './api.js';
import { decodeShare, encodeShare } from './lib/share.js';

const RESULT = {
  query: "SELECT * FROM orders WHERE status = 'PAID'",
  statementType: 'SELECT',
  summary: 'Reads data with a SELECT.',
  executionFlow: [
    { step: 1, operation: 'FROM', description: 'MySQL identifies the source table(s).', expression: 'orders', details: 'rows' },
    { step: 2, operation: 'WHERE', description: 'MySQL filters rows.', expression: "status = 'PAID'", details: 'filter' },
  ],
  executionPlan: [
    { id: 1, selectType: 'SIMPLE', table: 'orders', type: 'ALL', possibleKeys: null, key: null, keyLength: null, ref: null, rows: 19876, filtered: 10, extra: 'Using where' },
  ],
  jsonExecutionPlan: { selectId: 1, queryCost: 2012.5, orderingUsingFilesort: false, groupingUsingTemporaryTable: false },
  performanceIssues: [
    { severity: 'WARNING', title: 'Full Table Scan', table: 'orders', estimatedRows: 19876, description: 'd', reason: 'Reads every row.', suggestion: 'Add an index.' },
    { severity: 'NOTICE', title: 'Filesort Detected', description: 'd2', reason: 'Sorting costs.', suggestion: 'Index ORDER BY.' },
  ],
  indexSuggestions: [{ table: 'orders', columns: ['status'], ddl: 'CREATE INDEX idx_orders_status ON orders (status);', reason: 'why' }],
  notes: ['A note about execution order.'],
};

const CLEAN = { ...RESULT, performanceIssues: [], indexSuggestions: [], notes: [] };

function mockClipboard() {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  return writeText;
}

beforeEach(() => {
  localStorage.clear();
  window.history.replaceState(null, '', '/');
  document.documentElement.removeAttribute('data-theme');
  api.analyze.mockReset().mockResolvedValue(RESULT);
  api.fetchTables.mockReset().mockResolvedValue([{ name: 'orders', rows: 20000, columns: 5 }]);
  api.fetchColumns.mockReset().mockResolvedValue({ orders: ['id', 'status'] });
});

afterEach(() => cleanup());

const editor = () => screen.getByLabelText('SQL statement');

describe('first screen and verdict', () => {
  it('shows example cards, and one click analyses the example', async () => {
    render(<App />);
    expect(editor().value).toBe('');
    const card = screen.getByRole('button', { name: /Slow: full scan/ });
    fireEvent.click(card);

    await waitFor(() => expect(api.analyze).toHaveBeenCalledTimes(1));
    expect(api.analyze.mock.calls[0][0]).toMatch(/SELECT \* FROM orders/);
    expect(api.analyze.mock.calls[0][1]).toBe('');

    const verdict = await screen.findByRole('heading', { name: '1 warning, 1 notice, 1 index suggested' });
    expect(verdict).toBeTruthy();
    expect(screen.getByText('orders is read with a full table scan (about 19,876 rows).')).toBeTruthy();
    expect(screen.getByText('CREATE INDEX idx_orders_status ON orders (status);', { selector: '.verdict pre' })).toBeTruthy();
    // examples are replaced by the results
    expect(screen.queryByText('New here? Try an example')).toBeNull();
  });

  it('shows a green verdict when nothing is wrong', async () => {
    api.analyze.mockResolvedValue(CLEAN);
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT 1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    expect(await screen.findByRole('heading', { name: 'No problems found' })).toBeTruthy();
    expect(screen.queryByText('Biggest problem')).toBeNull();
  });

  it('runs on Ctrl+Enter from the editor', async () => {
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT 2' } });
    fireEvent.keyDown(editor(), { key: 'Enter', ctrlKey: true });
    await waitFor(() => expect(api.analyze).toHaveBeenCalledWith('SELECT 2', ''));
  });

  it('shows backend errors and keeps the example cards', async () => {
    api.analyze.mockRejectedValue(new Error('Table x does not exist'));
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT * FROM x' } });
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    expect((await screen.findByRole('alert')).textContent).toContain('Table x does not exist');
    expect(screen.queryByRole('tablist')).toBeNull();
  });

  it('rejects an empty statement without calling the backend', async () => {
    render(<App />);
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    expect((await screen.findByRole('alert')).textContent).toMatch(/enter a statement/i);
    expect(api.analyze).not.toHaveBeenCalled();
  });
});

describe('tabs', () => {
  async function analyzed() {
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT * FROM orders' } });
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    await screen.findByRole('tablist');
  }

  it('opens on Fixes and switches panels one at a time', async () => {
    await analyzed();
    const names = screen.getAllByRole('tab').map((t) => t.textContent);
    expect(names).toEqual(['Visual plan', 'Steps2', 'Fixes3', 'EXPLAIN', 'Notes']);
    expect(screen.getByRole('tab', { name: /Fixes/ }).getAttribute('aria-selected')).toBe('true');
    expect(within(screen.getByRole('tabpanel')).getByText('Full Table Scan')).toBeTruthy();
    expect(within(screen.getByRole('tabpanel')).getAllByText('Why it matters:', { exact: false })).toHaveLength(2); // one per finding

    fireEvent.click(screen.getByRole('tab', { name: /Steps/ }));
    const panel = screen.getByRole('tabpanel');
    expect(within(panel).getByText('FROM')).toBeTruthy();
    expect(within(panel).queryByText('Full Table Scan')).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: 'Notes' }));
    expect(within(screen.getByRole('tabpanel')).getByText('A note about execution order.')).toBeTruthy();
  });

  it('supports arrow keys, Home and End', async () => {
    await analyzed();
    const fixes = screen.getByRole('tab', { name: /Fixes/ });
    fixes.focus();
    fireEvent.keyDown(fixes, { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'EXPLAIN' }).getAttribute('aria-selected')).toBe('true');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'EXPLAIN' }), { key: 'End' });
    expect(screen.getByRole('tab', { name: 'Notes' }).getAttribute('aria-selected')).toBe('true');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Notes' }), { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'Visual plan' }).getAttribute('aria-selected')).toBe('true');
    // only the selected tab is in the tab order
    expect(screen.getAllByRole('tab').filter((t) => t.tabIndex === 0)).toHaveLength(1);
  });

  it('"See all fixes" returns to the Fixes tab', async () => {
    await analyzed();
    fireEvent.click(screen.getByRole('tab', { name: /Steps/ }));
    fireEvent.click(screen.getByRole('button', { name: /See all fixes/ }));
    expect(screen.getByRole('tab', { name: /Fixes/ }).getAttribute('aria-selected')).toBe('true');
  });

  it('hides tabs that have nothing to show', async () => {
    api.analyze.mockResolvedValue({ ...CLEAN, executionPlan: [], notes: [] });
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'CREATE INDEX i ON t (a)' } });
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    await screen.findByRole('tablist');
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Steps2', 'Fixes0']);
  });
});

describe('history', () => {
  it('records analyzed queries, reopens and removes them', async () => {
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT 1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    await screen.findByRole('tablist');

    const historyBtn = screen.getByRole('button', { name: /History/ });
    expect(historyBtn.textContent).toContain('1');
    fireEvent.click(historyBtn);
    const panel = screen.getByRole('region', { name: 'Recent queries' });
    expect(within(panel).getByText('SELECT 1')).toBeTruthy();

    // reopen: loads the query and analyses it again
    fireEvent.change(editor(), { target: { value: 'something else' } });
    api.analyze.mockClear();
    fireEvent.click(within(panel).getByText('SELECT 1'));
    await waitFor(() => expect(api.analyze).toHaveBeenCalledWith('SELECT 1', ''));
    expect(editor().value).toBe('SELECT 1');

    // remove
    fireEvent.click(screen.getByRole('button', { name: /History/ }));
    fireEvent.click(screen.getByRole('button', { name: /Remove from history/ }));
    expect(screen.getByText(/No queries yet/)).toBeTruthy();
  });

  it('persists across reloads and de-duplicates', async () => {
    const first = render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT 1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    await screen.findByRole('tablist');
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    await waitFor(() => expect(api.analyze).toHaveBeenCalledTimes(2));
    first.unmount();

    render(<App />);
    expect(screen.getByRole('button', { name: /History/ }).textContent).toContain('1');
  });

  it('does not save failed analyses', async () => {
    api.analyze.mockRejectedValue(new Error('boom'));
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT nope' } });
    fireEvent.click(screen.getByRole('button', { name: 'Analyze' }));
    await screen.findByRole('alert');
    expect(JSON.parse(localStorage.getItem('mysql-debugger:history:v1') || '[]')).toEqual([]);
  });
});

describe('share links', () => {
  it('copies a link that decodes back to the query', async () => {
    const writeText = mockClipboard();
    render(<App />);
    fireEvent.change(editor(), { target: { value: "SELECT 'é' FROM t" } });
    fireEvent.click(screen.getByRole('button', { name: /Share link/ }));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    const url = writeText.mock.calls[0][0];
    expect(url).toContain('#q=');
    expect(decodeShare(url.slice(url.indexOf('#')))).toEqual({ sql: "SELECT 'é' FROM t", schema: '' });
    expect(await screen.findByText('Link copied ✓')).toBeTruthy();
    expect(window.location.hash).toContain('#q=');
  });

  it('refuses links that are too long', async () => {
    const writeText = mockClipboard();
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'SELECT ' + 'col, '.repeat(4000) + '1' } });
    fireEvent.click(screen.getByRole('button', { name: /Share link/ }));
    expect(await screen.findByText(/too long for a link/)).toBeTruthy();
    expect(writeText).not.toHaveBeenCalled();
  });

  it('is disabled while the editor is empty', () => {
    render(<App />);
    expect(screen.getByRole('button', { name: /Share link/ }).disabled).toBe(true);
  });

  it('opening a share link fills the editor and analyses once (even under StrictMode)', async () => {
    window.history.replaceState(null, '', `/#q=${encodeShare({ sql: 'SELECT 42', schema: 'CREATE TABLE a (id INT);' })}`);
    render(
      <React.StrictMode>
        <App />
      </React.StrictMode>
    );
    await waitFor(() => expect(api.analyze).toHaveBeenCalled());
    expect(api.analyze).toHaveBeenCalledTimes(1);
    expect(api.analyze).toHaveBeenCalledWith('SELECT 42', 'CREATE TABLE a (id INT);');
    expect(await screen.findByRole('tablist')).toBeTruthy();
    expect(editor().value).toBe('SELECT 42');
  });

  it('ignores a damaged link', async () => {
    window.history.replaceState(null, '', '/#q=%%%garbage');
    render(<App />);
    expect(editor().value).toBe('');
    expect(api.analyze).not.toHaveBeenCalled();
    expect(screen.getByText('New here? Try an example')).toBeTruthy();
  });
});

describe('theme', () => {
  it('switches and remembers the choice', () => {
    render(<App />);
    const group = screen.getByRole('group', { name: 'Color theme' });
    fireEvent.click(within(group).getByRole('button', { name: 'Dark' }));
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(localStorage.getItem('mysql-debugger:theme')).toBe('dark');
    expect(within(group).getByRole('button', { name: 'Dark' }).getAttribute('aria-pressed')).toBe('true');

    fireEvent.click(within(group).getByRole('button', { name: 'Light' }));
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');

    fireEvent.click(within(group).getByRole('button', { name: 'Auto' }));
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
  });

  it('restores the saved theme on load', () => {
    localStorage.setItem('mysql-debugger:theme', 'dark');
    render(<App />);
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(screen.getByRole('button', { name: 'Dark' }).getAttribute('aria-pressed')).toBe('true');
  });
});

describe('editor toolbar', () => {
  it('Format SQL rewrites the statement', async () => {
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'select a,b from t where x=1' } });
    fireEvent.click(screen.getByRole('button', { name: /Format SQL/ }));
    await waitFor(() => expect(editor().value).toContain('SELECT'));
    expect(editor().value).toContain('\nFROM');
  });

  it('Format SQL explains why it cannot format trigger scripts', async () => {
    render(<App />);
    fireEvent.change(editor(), { target: { value: 'DELIMITER $$\nCREATE TRIGGER t AFTER INSERT ON a FOR EACH ROW SET @x = 1$$' } });
    fireEvent.click(screen.getByRole('button', { name: /Format SQL/ }));
    expect(await screen.findByText(/DELIMITER/, { selector: '[role="status"]' })).toBeTruthy();
  });

  it('example dropdown fills the editor without running', () => {
    render(<App />);
    fireEvent.change(screen.getByLabelText('Try an example'), { target: { value: '0' } });
    expect(editor().value.length).toBeGreaterThan(10);
    expect(api.analyze).not.toHaveBeenCalled();
  });

  it('tables panel lists tables and fills the editor', async () => {
    render(<App />);
    const chip = await screen.findByRole('button', { name: /orders/ });
    fireEvent.click(chip);
    expect(editor().value).toBe('SELECT * FROM `orders` LIMIT 10;');
  });
});
