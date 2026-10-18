package com.xiongx9527.askdata.datasource.engine;

import com.xiongx9527.askdata.datasource.DatasourceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 只读 SQL 校验单测（对齐 SQLBot check_sql_read 的允许/拒绝集合）。
 */
class SqlSafetyTest {

    private void assertAllowed(String sql) {
        assertDoesNotThrow(() -> SqlSafety.checkRead(sql, "mysql"));
    }

    private void assertRejected(String sql) {
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead(sql, "mysql"));
    }

    @Test
    void allowedStatements() {
        assertAllowed("SELECT 1");
        assertAllowed("select * from orders");
        assertAllowed("  WITH t AS (SELECT 1) SELECT * FROM t");
        assertAllowed("SELECT * FROM orders;");
        assertAllowed("SELECT * FROM orders;;  ");
        assertAllowed("SELECT COUNT(*) AS c FROM orders GROUP BY status");
    }

    @Test
    void rejectedWriteOperations() {
        assertRejected("INSERT INTO orders VALUES (1)");
        assertRejected("UPDATE orders SET status = 'x'");
        assertRejected("DELETE FROM orders");
        assertRejected("DROP TABLE orders");
        assertRejected("CREATE TABLE t (id INT)");
        assertRejected("ALTER TABLE orders ADD COLUMN x INT");
        assertRejected("TRUNCATE TABLE orders");
        assertRejected("GRANT ALL ON orders TO u");
        assertRejected("USE db1");
        assertRejected("SET @@x = 1");
        // 多语句中的写操作同样拒绝（对齐 sqlglot 多语句语义）
        assertRejected("SELECT 1; DROP TABLE orders");
        assertRejected("SELECT 1; DELETE FROM orders");
    }

    @Test
    void rejectedDisallowedReads() {
        assertRejected("SHOW TABLES");
        assertRejected("DESCRIBE orders");
        assertRejected("EXPLAIN SELECT 1");
        assertRejected("PRAGMA table_info('orders')");
    }

    @Test
    void rejectedDangerousPatternsAndFunctions() {
        assertRejected("SELECT * FROM orders INTO OUTFILE '/tmp/x'");
        assertRejected("SELECT * FROM orders INTO DUMPFILE '/tmp/x'");
        assertRejected("SELECT version()");
        assertRejected("SELECT current_user()");
        assertRejected("SELECT user()");
        assertRejected("SELECT database()");
        // pg 专用危险函数
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELECT pg_read_file('/etc/passwd')", "pg"));
        // mysql 专用危险函数
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELECT LOAD_FILE('/etc/passwd')", "mysql"));
    }

    @Test
    void emptySqlRejected() {
        assertRejected("");
        assertRejected("   ");
        assertRejected(";;");
    }
}
