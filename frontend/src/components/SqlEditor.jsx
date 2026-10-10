import { useEffect, useRef } from 'react';
import { basicSetup, EditorView } from 'codemirror';
import { Annotation, Compartment, EditorState, Prec } from '@codemirror/state';
import { keymap, placeholder as placeholderExt } from '@codemirror/view';
import { MySQL, sql } from '@codemirror/lang-sql';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';

// Colors come from CSS variables (see styles.css), so the editor follows the light/dark theme automatically.
const highlight = HighlightStyle.define([
  { tag: t.keyword, color: 'var(--syn-keyword)', fontWeight: '600' },
  { tag: [t.string, t.special(t.string)], color: 'var(--syn-string)' },
  { tag: [t.number, t.bool, t.null], color: 'var(--syn-number)' },
  { tag: [t.lineComment, t.blockComment], color: 'var(--syn-comment)', fontStyle: 'italic' },
  { tag: [t.typeName, t.standard(t.name)], color: 'var(--syn-type)' },
  { tag: [t.operator, t.punctuation], color: 'var(--muted)' },
]);

function makeTheme(minHeight) {
  return EditorView.theme({
    '&': {
      backgroundColor: 'var(--code)',
      color: 'var(--text)',
      border: '1px solid var(--border)',
      borderRadius: '12px',
      fontSize: '13.5px',
    },
    '&.cm-focused': { outline: '2px solid var(--accent)', outlineOffset: '1px' },
    '.cm-scroller': {
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
      lineHeight: '1.6',
      minHeight: `${minHeight}px`,
      maxHeight: '440px',
      overflow: 'auto',
    },
    '.cm-content': { caretColor: 'var(--text)', padding: '10px 0' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--text)' },
    '.cm-gutters': { backgroundColor: 'transparent', color: 'var(--muted)', border: 'none' },
    '.cm-activeLine': { backgroundColor: 'color-mix(in srgb, var(--accent) 7%, transparent)' },
    '.cm-activeLineGutter': { backgroundColor: 'transparent', color: 'var(--text)' },
    '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground': {
      background: 'color-mix(in srgb, var(--accent) 28%, transparent)',
    },
    '.cm-placeholder': { color: 'var(--muted)' },
    '.cm-tooltip': {
      backgroundColor: 'var(--card)',
      color: 'var(--text)',
      border: '1px solid var(--border)',
      borderRadius: '10px',
      boxShadow: 'var(--shadow)',
    },
    '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
      backgroundColor: 'var(--accent)',
      color: 'var(--accent-text)',
    },
  });
}

const NO_SCHEMA = {};

const sqlLanguage = (schema) => sql({ dialect: MySQL, schema, upperCaseKeywords: true });

// marks changes that come from the `value` prop so they are not reported back through onChange
const External = Annotation.define();

/**
 * SQL editor: syntax colors, line numbers, bracket matching and autocomplete.
 * `completionSchema` is { tableName: [columnName, ...] }.  Ctrl/⌘+Enter calls onRun.
 */
export default function SqlEditor({
  value,
  onChange,
  onRun,
  completionSchema = NO_SCHEMA,
  minHeight = 190,
  placeholder = '',
  ariaLabel = 'SQL',
}) {
  const host = useRef(null);
  const view = useRef(null);
  const language = useRef(new Compartment());
  const callbacks = useRef({ onChange, onRun });
  callbacks.current = { onChange, onRun };

  useEffect(() => {
    const v = new EditorView({
      parent: host.current,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          language.current.of(sqlLanguage(completionSchema)),
          syntaxHighlighting(highlight),
          makeTheme(minHeight),
          placeholderExt(placeholder),
          EditorView.lineWrapping,
          EditorView.contentAttributes.of({ 'aria-label': ariaLabel, 'aria-multiline': 'true' }),
          Prec.highest(
            keymap.of([
              {
                key: 'Mod-Enter',
                run: () => {
                  callbacks.current.onRun?.();
                  return true;
                },
              },
            ])
          ),
          EditorView.updateListener.of((u) => {
            if (u.docChanged && !u.transactions.some((tr) => tr.annotation(External))) {
              callbacks.current.onChange?.(u.state.doc.toString());
            }
          }),
        ],
      }),
    });
    view.current = v;
    return () => {
      v.destroy();
      view.current = null;
    };
    // the editor is created once; later prop changes are applied by the effects below
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // text changed from outside (example, history, share link, Format SQL)
  useEffect(() => {
    const v = view.current;
    if (v && value !== v.state.doc.toString()) {
      v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value }, annotations: External.of(true) });
    }
  }, [value]);

  // new tables / columns for autocomplete
  useEffect(() => {
    view.current?.dispatch({ effects: language.current.reconfigure(sqlLanguage(completionSchema)) });
  }, [completionSchema]);

  return <div ref={host} className="sql-editor" />;
}
