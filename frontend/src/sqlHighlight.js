// Finds which part of the user's SQL an execution-flow step refers to, so the walk-through can highlight it.
// The backend's step.expression is a literal piece of the query (the text after the clause keyword), so we look
// for that text directly AFTER its keyword: this avoids matching the same word elsewhere in the statement.

const KEYWORD_BEFORE = {
  FROM: /FROM\s*$/i,
  WHERE: /WHERE\s*$/i,
  'GROUP BY': /GROUP\s+BY\s*$/i,
  HAVING: /HAVING\s*$/i,
  'ORDER BY': /ORDER\s+BY\s*$/i,
  LIMIT: /LIMIT\s*$/i,
  SELECT: /SELECT(\s+(DISTINCT|ALL|SQL_[A-Z_]+))*\s*$/i,
};

/** @returns {{start:number,end:number}|null} the range of `query` to highlight for this step */
export function locateStep(query, step) {
  if (!query || !step) return null;
  const op = step.operation;
  const expr = (step.expression || '').trim();

  if (op === 'DISTINCT') {
    const m = /\bDISTINCT\b/i.exec(query);
    return m ? { start: m.index, end: m.index + m[0].length } : null;
  }
  if (!expr) return null;

  if (op === 'JOIN') {
    const i = query.indexOf(expr);
    return i < 0 ? null : { start: i, end: i + expr.length };
  }

  const keyword = KEYWORD_BEFORE[op];
  if (!keyword) return null; // WINDOW, CTE, UNION, DML steps...: nothing reliable to point at

  let from = 0;
  for (;;) {
    const i = query.indexOf(expr, from);
    if (i < 0) return null;
    const m = keyword.exec(query.slice(0, i));
    if (m) return { start: m.index, end: i + expr.length };
    from = i + 1;
  }
}

/** Splits the query into [before, highlighted, after] for rendering. */
export function splitAround(query, span) {
  if (!span) return [query, '', ''];
  return [query.slice(0, span.start), query.slice(span.start, span.end), query.slice(span.end)];
}
