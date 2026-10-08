-- Demo schema for trying out the debugger (MySQL 8.0+)
CREATE DATABASE IF NOT EXISTS mysql_debugger;
USE mysql_debugger;

DROP TABLE IF EXISTS order_audit;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS customers;

CREATE TABLE customers (
    id    INT AUTO_INCREMENT PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    city  VARCHAR(80)  NOT NULL
);

-- customer_id is indexed; status and created_at are NOT (so you can see full scans / filesort)
CREATE TABLE orders (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT           NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    created_at  DATETIME      NOT NULL,
    INDEX idx_orders_customer (customer_id),
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers (id)
);

-- target of the audit-trigger example in the UI
CREATE TABLE order_audit (
    id         INT AUTO_INCREMENT PRIMARY KEY,
    order_id   INT         NOT NULL,
    old_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    changed_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

SET SESSION cte_max_recursion_depth = 20000;

INSERT INTO customers (name, email, city)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT CONCAT('Customer ', n),
       CONCAT('user', n, '@example.com'),
       ELT(1 + n MOD 5, 'Hyderabad', 'Bengaluru', 'Chennai', 'Mumbai', 'Delhi')
FROM seq;

INSERT INTO orders (customer_id, amount, status, created_at)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 20000)
SELECT 1 + n MOD 1000,
       ROUND(10 + (n * 37 MOD 990), 2),
       ELT(1 + n MOD 3, 'PAID', 'PENDING', 'CANCELLED'),
       DATE_SUB(NOW(), INTERVAL (n MOD 365) DAY)
FROM seq;

ANALYZE TABLE customers, orders;
