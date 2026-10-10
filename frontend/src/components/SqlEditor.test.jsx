import React from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render } from '@testing-library/react';
import { EditorView } from 'codemirror';
import { CompletionContext } from '@codemirror/autocomplete';
import SqlEditor from './SqlEditor.jsx';

// jsdom has no layout engine; CodeMirror only needs these to exist.
beforeAll(() => {
  const rect = { x: 0, y: 0, width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0, toJSON() {} };
  Range.prototype.getBoundingClientRect = () => rect;
  Range.prototype.getClientRects = () => ({ length: 0, item: () => null, [Symbol.iterator]: function* () {} });
});
afterEach(() => cleanup());

const viewOf = (container) => EditorView.findFromDOM(container.querySelector('.cm-editor'));

async function completionsAt(view, pos) {
  const context = new CompletionContext(view.state, pos, true);
  const labels = [];
  for (const source of view.state.languageDataAt('autocomplete', pos)) {
    const result = await source(context);
    if (result) labels.push(...result.options.map((o) => o.label));
  }
  return labels;
}

describe('SqlEditor (real CodeMirror)', () => {
  it('renders the text with line numbers and an accessible label', () => {
    const { container } = render(<SqlEditor value={'SELECT 1\nFROM t'} onChange={() => {}} ariaLabel="SQL statement" />);
    expect(viewOf(container).state.doc.toString()).toBe('SELECT 1\nFROM t');
    expect(container.querySelector('.cm-lineNumbers')).toBeTruthy();
    expect(container.querySelector('.cm-content').getAttribute('aria-label')).toBe('SQL statement');
    expect(container.querySelectorAll('.cm-line')).toHaveLength(2);
  });

  it('colors SQL tokens through the theme variables', () => {
    const { container } = render(<SqlEditor value={"SELECT 'x', 42 FROM t"} onChange={() => {}} />);
    // tokens are wrapped in highlight spans ...
    expect(container.querySelectorAll('.cm-line span').length).toBeGreaterThan(2);
    // ... and the generated rules use the page's CSS variables, so light/dark themes recolor them
    const css = [...document.querySelectorAll('style')].map((n) => n.textContent).join('\n');
    for (const v of ['--syn-keyword', '--syn-string', '--syn-number', '--syn-comment']) {
      expect(css).toContain(`var(${v})`);
    }
  });

  it('reports user edits, but not changes that came from the value prop', () => {
    const onChange = vi.fn();
    const { container, rerender } = render(<SqlEditor value="SELECT 1" onChange={onChange} />);
    const view = viewOf(container);

    act(() => view.dispatch({ changes: { from: 8, insert: ' + 1' } }));
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenCalledWith('SELECT 1 + 1');

    onChange.mockClear();
    rerender(<SqlEditor value="DELETE FROM t" onChange={onChange} />);
    expect(view.state.doc.toString()).toBe('DELETE FROM t');
    expect(onChange).not.toHaveBeenCalled();
  });

  it('runs on Ctrl+Enter and does not insert a newline', () => {
    const onRun = vi.fn();
    const { container } = render(<SqlEditor value="SELECT 1" onChange={() => {}} onRun={onRun} />);
    const content = container.querySelector('.cm-content');
    fireEvent.keyDown(content, { key: 'Enter', ctrlKey: true });
    expect(onRun).toHaveBeenCalledTimes(1);
    expect(viewOf(container).state.doc.toString()).toBe('SELECT 1');
  });

  it('autocompletes table and column names from the schema, and picks up schema changes', async () => {
    const { container, rerender } = render(
      <SqlEditor value="SELECT * FROM or" onChange={() => {}} completionSchema={{ orders: ['id', 'status'], customers: ['id', 'city'] }} />
    );
    const view = viewOf(container);
    const tables = await completionsAt(view, 'SELECT * FROM or'.length);
    expect(tables).toContain('orders');
    expect(tables).toContain('customers');

    const columns = await completionsAt(
      (() => {
        act(() => view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: 'SELECT orders.st' } }));
        return view;
      })(),
      'SELECT orders.st'.length
    );
    expect(columns).toContain('status');

    // a new schema (for example after pasting CREATE TABLE statements) is used without recreating the editor
    rerender(<SqlEditor value={view.state.doc.toString()} onChange={() => {}} completionSchema={{ invoices: ['total'] }} />);
    act(() => view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: 'SELECT * FROM inv' } }));
    expect(await completionsAt(view, 'SELECT * FROM inv'.length)).toContain('invoices');
    expect(viewOf(container)).toBe(view);
  });

  it('suggests SQL keywords in upper case', async () => {
    const { container } = render(<SqlEditor value="SEL" onChange={() => {}} />);
    const labels = await completionsAt(viewOf(container), 3);
    expect(labels).toContain('SELECT');
  });

  it('cleans up the editor on unmount', () => {
    const { container, unmount } = render(<SqlEditor value="SELECT 1" onChange={() => {}} />);
    expect(container.querySelector('.cm-editor')).toBeTruthy();
    unmount();
    expect(container.querySelector('.cm-editor')).toBeNull();
  });
});
