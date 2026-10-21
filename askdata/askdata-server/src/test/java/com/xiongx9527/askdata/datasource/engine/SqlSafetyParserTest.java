package com.xiongx9527.askdata.datasource.engine;

import com.xiongx9527.askdata.datasource.DatasourceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A5 SQL 安全校验（对齐 SQLBot {@code check_sql_read} 的 AST 语义）：
 * CTE 内写操作拒绝、解析失败拒绝、危险函数拒绝、非 SELECT/WITH 拒绝；正常 SELECT 放行。
 */
class SqlSafetyParserTest {

    @Test
    void cteWithDeleteRejected() {
        // 经典绕过：CTE 内隐藏 DELETE，首关键字仍是 WITH
        String sql = "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x";
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead(sql, "mysql"));
    }

    @Test
    void plainDeleteRejected() {
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("DELETE FROM t WHERE id = 1", "mysql"));
    }

    @Test
    void plainInsertRejected() {
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("INSERT INTO t VALUES (1)", "mysql"));
    }

    @Test
    void plainUpdateRejected() {
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("UPDATE t SET a = 1", "mysql"));
    }

    @Test
    void parseFailureRejected() {
        // 无法解析的 SQL：保守拒绝
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELCT * FROM t WHERE", "mysql"));
    }

    @Test
    void unbalancedParensRejected() {
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELECT * FROM t WHERE (a = 1", "mysql"));
    }

    @Test
    void dangerousFunctionRejected() {
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("SELECT version() FROM t", "mysql"));
    }

    @Test
    void explainRejectedByDefault() {
        // 默认不允许元数据查询（对齐 SQLBOT_ALLOW_METADATA_QUERIES=False）
        assertThrows(DatasourceException.class, () -> SqlSafety.checkRead("EXPLAIN SELECT * FROM t", "mysql"));
    }

    @Test
    void normalSelectAllowed() {
        assertDoesNotThrow(() -> SqlSafety.checkRead("SELECT id, name FROM t WHERE age > 18", "mysql"));
    }

    @Test
    void cteSelectAllowed() {
        assertDoesNotThrow(() -> SqlSafety.checkRead(
                "WITH x AS (SELECT id FROM t) SELECT * FROM x WHERE x.id > 1", "mysql"));
    }

    @Test
    void unionSelectAllowed() {
        assertDoesNotThrow(() -> SqlSafety.checkRead(
                "SELECT id FROM t1 UNION SELECT id FROM t2", "mysql"));
    }

    @Test
    void trailingSemicolonTrimmed() {
        assertDoesNotThrow(() -> SqlSafety.checkRead("SELECT 1;", "mysql"));
    }
}
