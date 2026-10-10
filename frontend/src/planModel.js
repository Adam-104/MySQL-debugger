// Turns MySQL's EXPLAIN rows (+ JSON plan flags + the logical flow) into what the "Visual plan" card draws.
// Pure functions, no React. All row numbers are MySQL's ESTIMATES from table statistics.

export function accessInfo(type) {
  switch ((type || '').toLowerCase()) {
    case 'all':
      return { label: 'Full table scan', tone: 'bad', icon: '✕', hint: 'Reads every row of the table.' };
    case 'index':
      return { label: 'Full index scan', tone: 'meh', icon: '!', hint: 'Reads every entry of an index.' };
    case 'range':
      return { label: 'Index range scan', tone: 'info', icon: '◆', hint: 'Reads a slice of an index.' };
    case 'ref':
    case 'eq_ref':
      return { label: 'Index lookup', tone: 'good', icon: '✓', hint: 'Jumps straight to matching rows via an index.' };
    case 'const':
    case 'system':
      return { label: 'Single-row lookup', tone: 'good', icon: '✓', hint: 'At most one row, found via a key.' };
    case '':
      return { label: 'No table access', tone: 'info', icon: '◆', hint: 'MySQL can answer without reading a table.' };
    default:
      return { label: type, tone: 'info', icon: '◆', hint: 'See the EXPLAIN table below.' };
  }
}

// Extra flags drawn as chips on a table node. filesort / temporary become their own pipeline steps instead.
function extraChips(extra) {
  const chips = [];
  for (const raw of (extra || '').split(';')) {
    const e = raw.trim();
    const l = e.toLowerCase();
    if (!e || l.includes('filesort') || l.includes('temporary')) continue;
    if (l === 'using where') chips.push({ label: 'Filters rows with WHERE', tone: 'neutral' });
    else if (l.startsWith('using index condition')) chips.push({ label: 'Index condition pushdown', tone: 'info' });
    else if (l === 'using index') chips.push({ label: 'Covering index (no table reads)', tone: 'good' });
    else if (l.includes('join buffer')) chips.push({ label: 'No usable join index (join buffer)', tone: 'meh' });
    else chips.push({ label: e, tone: 'neutral' });
  }
  return chips;
}

export function limitFromFlow(flow = []) {
  const step = flow.find((s) => s.operation === 'LIMIT');
  if (!step) return null;
  const text = String(step.expression || '').replace(/^\s*LIMIT\s+/i, '');
  const offsetComma = /(\d+)\s*,\s*(\d+)/.exec(text);
  const m = offsetComma ? offsetComma[2] : /(\d+)/.exec(text)?.[1];
  return m ? Number(m) : null;
}

export function fmt(n) {
  if (n == null || Number.isNaN(n)) return '–';
  if (n > 0 && n < 1) return '<1';
  return Math.round(n).toLocaleString();
}

export function buildPlan(plan = [], json = null, flow = []) {
  if (!plan.length) return null;

  // ---- diagram: group EXPLAIN rows by select id, in order of appearance
  const groups = [];
  for (const r of plan) {
    let g = groups.find((x) => x.id === r.id);
    if (!g) {
      g = { id: r.id, selectType: r.selectType, nodes: [] };
      groups.push(g);
    }
    g.nodes.push({
      table: r.table,
      selectType: r.selectType,
      access: accessInfo(r.type),
      rawType: r.type,
      key: r.key,
      possibleKeys: r.possibleKeys,
      rows: r.rows,
      filtered: r.filtered,
      chips: extraChips(r.extra),
    });
  }
  groups.forEach((g, i) => {
    g.title = i === 0 ? 'Main query' : `${g.selectType ? g.selectType.toLowerCase() : 'subquery'} (select ${g.id})`;
  });

  // ---- extra pipeline steps
  const extras = plan.map((r) => (r.extra || '').toLowerCase()).join(';');
  const post = [];
  if (json?.orderingUsingFilesort || json?.groupingUsingFilesort || extras.includes('filesort')) {
    post.push({ label: 'Extra sort pass', detail: 'filesort: rows are sorted after being read', tone: 'meh', icon: '!' });
  }
  if (json?.groupingUsingTemporaryTable || extras.includes('temporary')) {
    post.push({ label: 'Temporary table', detail: 'rows are collected in a temp table for grouping/DISTINCT', tone: 'meh', icon: '!' });
  }

  // ---- funnel: estimated rows through the main select's tables
  const main = groups[0].nodes.filter((n) => n.table && n.rows != null);
  const stages = [];
  let flowing = 1;
  let worst = null;
  let rowsRead = 0;
  for (const n of main) {
    const examined = flowing * n.rows;
    rowsRead += examined;
    const keepPct = n.filtered == null ? 100 : n.filtered;
    const kept = (examined * keepPct) / 100;
    stages.push({ label: `Read ${n.table}`, value: examined, detail: `${n.access.label}` });
    if (keepPct < 99.9) {
      stages.push({ label: `Keep after filters on ${n.table}`, value: kept, detail: `${Math.round(keepPct)}% of rows pass` });
    }
    if (examined >= 100 && kept / examined < 0.5 && (!worst || examined - kept > worst.examined - worst.kept)) {
      worst = { table: n.table, examined, kept, pct: Math.round((kept / examined) * 100) };
    }
    flowing = kept;
  }

  const ops = new Set(flow.map((s) => s.operation));
  const changesCount = ops.has('GROUP BY') || ops.has('DISTINCT') || ops.has('HAVING') || ops.has('UNION');
  const limit = limitFromFlow(flow);
  let note = '';
  if (changesCount) {
    note = 'GROUP BY / DISTINCT / HAVING change the final row count, so the funnel stops before them.';
  } else if (limit != null && stages.length) {
    stages.push({ label: `Returned (LIMIT ${limit})`, value: Math.min(flowing, limit), detail: 'rows sent to the client' });
  }

  return {
    groups,
    post,
    rowsRead: main.length ? rowsRead : null,
    funnel: stages.length >= 2 ? { stages, note, worst } : null,
  };
}

// ---------------------------------------------------------------------------------------------------------------
// "What if I add this index?": compare the analysis before and after
// ---------------------------------------------------------------------------------------------------------------

function accessRank(type) {
  switch ((type || '').toLowerCase()) {
    case 'all': return 0;
    case 'index': return 1;
    case 'range': return 2;
    case 'ref':
    case 'eq_ref': return 3;
    case 'const':
    case 'system': return 4;
    default: return 2;
  }
}

export function summarize(resp) {
  const model = buildPlan(resp?.executionPlan, resp?.jsonExecutionPlan, resp?.executionFlow);
  if (!model) return null;
  return {
    tables: model.groups[0].nodes
      .filter((n) => n.table)
      .map((n) => ({ table: n.table, access: n.access, rawType: n.rawType, key: n.key })),
    rowsRead: model.rowsRead,
    cost: resp.jsonExecutionPlan?.queryCost ?? null,
    filesort: model.post.some((p) => p.label === 'Extra sort pass'),
    temp: model.post.some((p) => p.label === 'Temporary table'),
  };
}

function lowerIsBetter(before, after) {
  if (before == null || after == null) return 'same';
  if (after < before * 0.98) return 'better';
  if (after > before * 1.02) return 'worse';
  return 'same';
}

function flagTrend(before, after) {
  if (before === after) return 'same';
  return before && !after ? 'better' : 'worse';
}

/** Rows of a before/after table plus a one-sentence verdict. Returns null when either side has no table plan. */
export function compareWhatIf(beforeResp, afterResp) {
  const b = summarize(beforeResp);
  const a = summarize(afterResp);
  if (!b || !a) return null;

  const rows = [];
  const accessChanges = [];
  const tableRows = [];
  for (const bt of b.tables) {
    const at = a.tables.find((t) => t.table === bt.table);
    if (!at) continue;
    const rankDiff = accessRank(at.rawType) - accessRank(bt.rawType);
    const accessTrend = rankDiff > 0 ? 'better' : rankDiff < 0 ? 'worse' : 'same';
    const keyChanged = (bt.key || '') !== (at.key || '');
    const keyTrend = keyChanged ? (at.key ? 'better' : 'worse') : 'same';
    if (accessTrend !== 'same') accessChanges.push({ table: bt.table, from: bt.access.label, to: at.access.label, trend: accessTrend });
    tableRows.push(
      { label: `${bt.table}: how it is read`, before: bt.access.label, after: at.access.label, trend: accessTrend, changed: accessTrend !== 'same' || keyChanged },
      { label: `${bt.table}: index used`, before: bt.key || 'none', after: at.key || 'none', trend: keyTrend, changed: keyChanged }
    );
  }
  const changedRows = tableRows.filter((r) => r.changed);
  rows.push(...(changedRows.length ? changedRows : tableRows.slice(0, 2)));

  const rowsTrend = lowerIsBetter(b.rowsRead, a.rowsRead);
  const costTrend = lowerIsBetter(b.cost, a.cost);
  rows.push({ label: 'Estimated rows read', before: fmt(b.rowsRead), after: fmt(a.rowsRead), trend: rowsTrend });
  if (b.cost != null || a.cost != null) {
    const f = (n) => (n == null ? '–' : Number(n).toLocaleString(undefined, { maximumFractionDigits: 1 }));
    rows.push({ label: 'Estimated query cost', before: f(b.cost), after: f(a.cost), trend: costTrend });
  }
  rows.push({ label: 'Extra sort pass (filesort)', before: b.filesort ? 'Yes' : 'No', after: a.filesort ? 'Yes' : 'No', trend: flagTrend(b.filesort, a.filesort) });
  rows.push({ label: 'Temporary table', before: b.temp ? 'Yes' : 'No', after: a.temp ? 'Yes' : 'No', trend: flagTrend(b.temp, a.temp) });

  const trends = rows.map((r) => r.trend);
  const better = trends.includes('better');
  const worse = trends.includes('worse');

  let verdict;
  if (better && !worse) {
    const parts = [];
    for (const c of accessChanges.filter((x) => x.trend === 'better')) parts.push(`${c.table} goes from "${c.from}" to "${c.to}"`);
    if (rowsTrend === 'better' && b.rowsRead > 0) parts.push(`about ${Math.round((1 - a.rowsRead / b.rowsRead) * 100)}% fewer rows are read`);
    if (b.filesort && !a.filesort) parts.push('the extra sort disappears');
    if (b.temp && !a.temp) parts.push('the temporary table disappears');
    verdict = { tone: 'good', title: 'This index helps', text: parts.length ? `${parts.join('; ')}.` : 'The estimated cost goes down.' };
  } else if (worse && !better) {
    verdict = { tone: 'bad', title: 'This index makes it worse', text: 'The plan looks more expensive with the index. Do not add it for this query.' };
  } else if (better && worse) {
    verdict = { tone: 'meh', title: 'Mixed result', text: 'Some things improve and others get worse. Check the table below before adding it.' };
  } else {
    verdict = {
      tone: 'info',
      title: 'No change in the plan',
      text: 'MySQL picks the same plan with this index. On very small tables it prefers a full scan, so try more realistic data (paste more INSERT rows) before judging the index.',
    };
  }

  return { rows, verdict, costBefore: b.cost, costAfter: a.cost };
}
