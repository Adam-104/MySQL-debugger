// "Format SQL" button. sql-formatter is loaded on demand so it doesn't weigh down the first page load.

export async function formatSql(sql) {
  if (/^\s*DELIMITER\b/im.test(sql)) {
    throw new Error('Formatting is skipped for scripts that use DELIMITER (trigger scripts).');
  }
  const { format } = await import('sql-formatter');
  try {
    return format(sql, { language: 'mysql', keywordCase: 'upper', tabWidth: 2, linesBetweenQueries: 1 });
  } catch (e) {
    throw new Error(`Could not format this SQL: ${e.message.split('\n')[0]}`);
  }
}
