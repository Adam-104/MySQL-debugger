// Turns an analysis result into the short "verdict" shown above the tabs.
// Pure function: no React, no network.

const INDEX_RELATED = /scan|filesort|index|join buffer|temporary/i;

function plural(n, one, many) {
  return `${n} ${n === 1 ? one : many}`;
}

function fmt(n) {
  return Number(n).toLocaleString('en-US');
}

/** One plain sentence for the most important finding. */
function describeIssue(issue) {
  if (/full table scan/i.test(issue.title) && issue.table) {
    const rows = issue.estimatedRows > 0 ? ` (about ${fmt(issue.estimatedRows)} rows)` : '';
    return `${issue.table} is read with a full table scan${rows}.`;
  }
  return issue.description || issue.title;
}

/**
 * @returns {{
 *   tone: 'good'|'info'|'meh'|'bad',
 *   headline: string,
 *   counts: {warning:number, notice:number, info:number},
 *   indexCount: number,
 *   biggest: {title:string, text:string}|null,
 *   topFix: {kind:'index', ddl:string, text:string}|{kind:'text', text:string}|null
 * }}
 */
export function buildVerdict(result) {
  const issues = result?.performanceIssues || [];
  const suggestions = result?.indexSuggestions || [];

  const counts = { warning: 0, notice: 0, info: 0 };
  for (const i of issues) {
    if (i.severity === 'WARNING') counts.warning++;
    else if (i.severity === 'NOTICE') counts.notice++;
    else counts.info++;
  }

  const parts = [];
  if (counts.warning) parts.push(plural(counts.warning, 'warning', 'warnings'));
  if (counts.notice) parts.push(plural(counts.notice, 'notice', 'notices'));
  if (counts.info) parts.push(plural(counts.info, 'tip', 'tips'));
  if (suggestions.length) parts.push(plural(suggestions.length, 'index suggested', 'indexes suggested'));

  const tone = counts.warning ? 'bad' : counts.notice ? 'meh' : counts.info || suggestions.length ? 'info' : 'good';
  const headline = parts.length ? parts.join(', ') : 'No problems found';

  // issues arrive sorted by severity (WARNING first), so the first one is the biggest
  const top = issues[0] || null;
  const biggest = top ? { title: top.title, text: describeIssue(top) } : null;

  let topFix = null;
  if (suggestions.length && (!top || INDEX_RELATED.test(top.title))) {
    const s = suggestions[0];
    topFix = { kind: 'index', ddl: s.ddl, text: s.reason };
  } else if (top?.suggestion) {
    topFix = { kind: 'text', text: top.suggestion };
  } else if (suggestions.length) {
    topFix = { kind: 'index', ddl: suggestions[0].ddl, text: suggestions[0].reason };
  }

  return { tone, headline, counts, indexCount: suggestions.length, biggest, topFix };
}
