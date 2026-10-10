-- Least-privilege account for the debugger (used by docker-compose).
--
-- The app never executes the statements it analyses; it only runs EXPLAIN on them.
-- MySQL requires the privileges of the explained statement for EXPLAIN, so DML needs
-- INSERT/UPDATE/DELETE. If you only want to analyse SELECTs, grant SELECT only.
CREATE USER IF NOT EXISTS 'debugger'@'%' IDENTIFIED BY 'debugger_pw';
GRANT SELECT, INSERT, UPDATE, DELETE, SHOW VIEW ON mysql_debugger.* TO 'debugger'@'%';

-- "Paste your own schema" mode creates a temporary schema named sbx_<random> per request and drops it afterwards.
GRANT ALL PRIVILEGES ON `sbx\_%`.* TO 'debugger'@'%';
FLUSH PRIVILEGES;
