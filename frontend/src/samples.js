// Examples for every kind of statement the backend understands.
// They match db/01-sample-data.sql (customers / orders).
export const SAMPLES = [
  {
    group: 'SELECT',
    label: 'Sort without an index',
    card: { title: 'Sort without an index', blurb: 'ORDER BY on a column with no index forces MySQL to sort every row.', icon: '↕️', tone: 'meh' },
    sql: `SELECT id, amount FROM orders
ORDER BY created_at DESC
LIMIT 20;`,
  },
  {
    group: 'SELECT',
    label: 'Join + unindexed filter',
    card: { title: 'Join + unindexed filter', blurb: 'A join is only as fast as the filter that feeds it.', icon: '🔗', tone: 'bad' },
    sql: `SELECT o.id, o.amount
FROM customers c
JOIN orders o ON o.customer_id = c.id
WHERE c.email = 'user7@example.com';`,
  },
  {
    group: 'SELECT',
    label: 'Function hides the index',
    card: { title: 'Function hides the index', blurb: 'LOWER(email) = … stops MySQL from using an index on email.', icon: '🙈', tone: 'bad' },
    sql: `SELECT id, name FROM customers
WHERE LOWER(email) = 'user7@example.com';`,
  },
  {
    group: 'SELECT',
    label: 'Full scan + filesort',
    card: { title: 'Slow: full scan', blurb: 'Filtering on a column with no index reads every row of the table.', icon: '🐌', tone: 'bad' },
    sql: `SELECT * FROM orders
WHERE status = 'PAID' AND amount > 100
ORDER BY created_at DESC
LIMIT 20;`,
  },
  {
    group: 'SELECT',
    label: 'Join + group by',
    sql: `SELECT c.city, COUNT(*) AS total_orders, SUM(o.amount) AS revenue
FROM customers c
JOIN orders o ON o.customer_id = c.id
WHERE o.status = 'PAID'
GROUP BY c.city
HAVING SUM(o.amount) > 1000
ORDER BY revenue DESC;`,
  },
  {
    group: 'SELECT',
    label: 'Function on column + wildcard',
    sql: `SELECT id, name FROM customers
WHERE LOWER(email) = 'user7@example.com'
   OR name LIKE '%Customer 9%';`,
  },
  {
    group: 'CTE & window',
    label: 'CTE + window functions',
    card: { title: 'Ranking with window functions', blurb: 'See how a CTE and ROW_NUMBER() / running totals are evaluated.', icon: '🪟', tone: 'info' },
    sql: `WITH paid AS (
  SELECT customer_id, amount, created_at
  FROM orders
  WHERE status = 'PAID'
)
SELECT customer_id, amount,
       ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY created_at DESC) AS rn,
       SUM(amount)  OVER (PARTITION BY customer_id ORDER BY created_at)      AS running_total
FROM paid
ORDER BY customer_id, rn;`,
  },
  {
    group: 'CTE & window',
    label: 'Recursive CTE',
    sql: `WITH RECURSIVE numbers AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM numbers WHERE n < 10
)
SELECT n, n * n AS square FROM numbers;`,
  },
  {
    group: 'Data changes',
    label: 'UPDATE without WHERE',
    card: { title: 'Risky UPDATE', blurb: 'No WHERE means every row is changed and locked.', icon: '💣', tone: 'bad' },
    sql: `UPDATE orders SET status = 'ARCHIVED';`,
  },
  {
    group: 'Data changes',
    label: 'DELETE with LIMIT',
    sql: `DELETE FROM orders
WHERE created_at < '2024-01-01'
LIMIT 1000;`,
  },
  {
    group: 'Data changes',
    label: 'INSERT ... SELECT',
    sql: `INSERT INTO orders (customer_id, amount, status, created_at)
SELECT id, 50.00, 'PENDING', NOW()
FROM customers
WHERE city = 'Hyderabad'
ON DUPLICATE KEY UPDATE amount = amount + 1;`,
  },
  {
    group: 'Views',
    label: 'View with GROUP BY',
    card: { title: 'A view that cannot merge', blurb: 'Why a view with GROUP BY is rebuilt on every use.', icon: '👁️', tone: 'meh' },
    sql: `CREATE OR REPLACE ALGORITHM = MERGE VIEW v_city_revenue AS
SELECT c.city, SUM(o.amount) AS revenue
FROM customers c
JOIN orders o ON o.customer_id = c.id
GROUP BY c.city;`,
  },
  {
    group: 'Triggers',
    label: 'Audit trigger',
    card: { title: 'Audit trigger', blurb: 'When a trigger fires, and what it costs for every row.', icon: '🔔', tone: 'info' },
    sql: `DELIMITER $$
CREATE TRIGGER trg_orders_audit
AFTER UPDATE ON orders
FOR EACH ROW
BEGIN
  INSERT INTO order_audit (order_id, old_status, new_status)
  VALUES (OLD.id, OLD.status, NEW.status);
END$$
DELIMITER ;`,
  },
  {
    group: 'Indexes',
    label: 'Composite index',
    card: { title: 'Add a composite index', blurb: 'Column order decides which queries an index can serve.', icon: '🧩', tone: 'good' },
    sql: `CREATE INDEX idx_orders_status_created
ON orders (status, created_at);`,
  },
];
