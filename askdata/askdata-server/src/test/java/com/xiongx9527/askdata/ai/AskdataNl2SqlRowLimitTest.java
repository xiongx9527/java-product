package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A6：行上限分页按方言注入（MySQL/PG/SQLite/H2/CK 等 LIMIT；Oracle/达梦 FETCH FIRST；
 * SQL Server OFFSET/FETCH），且生成的 SQL 在 H2 上确为合法可执行（离体验证方言分页语法）。
 */
class AskdataNl2SqlRowLimitTest {

    @Test
    void mysqlPgUseLimit() {
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.MYSQL).endsWith(" LIMIT 200"));
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.PG).endsWith(" LIMIT 200"));
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.H2).endsWith(" LIMIT 200"));
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.CK).endsWith(" LIMIT 200"));
    }

    @Test
    void oracleAndDmUseFetchFirst() {
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.ORACLE)
                .endsWith(" FETCH FIRST 200 ROWS ONLY"));
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.DM)
                .endsWith(" FETCH FIRST 200 ROWS ONLY"));
        // Oracle 方言绝不追加 LIMIT（LIMIT 在 Oracle 非法）
        assertFalse(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.ORACLE).contains("LIMIT"));
    }

    @Test
    void sqlServerUsesOffsetFetch() {
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.SQLSERVER)
                .endsWith(" OFFSET 0 ROWS FETCH NEXT 200 ROWS ONLY"));
    }

    @Test
    void existingPaginationUntouched() {
        assertEquals("SELECT id FROM t LIMIT 5",
                AskdataNl2Sql.withRowLimit("SELECT id FROM t LIMIT 5", DatasourceType.ORACLE));
        assertEquals("SELECT id FROM t FETCH FIRST 5 ROWS ONLY",
                AskdataNl2Sql.withRowLimit("SELECT id FROM t FETCH FIRST 5 ROWS ONLY", DatasourceType.MYSQL));
    }

    @Test
    void trailingSemicolonStripped() {
        String out = AskdataNl2Sql.withRowLimit("SELECT id FROM t;", DatasourceType.MYSQL);
        assertFalse(out.contains(";"), out);
        assertTrue(out.endsWith(" LIMIT 200"), out);
    }

    @Test
    void nullDialectFallsBackToLimit() {
        assertTrue(AskdataNl2Sql.withRowLimit("SELECT id FROM t", null).endsWith(" LIMIT 200"));
    }

    // ---- 生成的方言分页 SQL 在 H2 上确为合法可执行 ----

    @Test
    void limitSqlExecutesOnH2() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:nl2sql_rl_limit;DB_CLOSE_DELAY=-1", "sa", "")) {
            seed(c);
            String sql = AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.MYSQL);
            assertEquals(3, count(c, sql));
        }
    }

    @Test
    void fetchFirstSqlExecutesOnH2() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:nl2sql_rl_fetch;DB_CLOSE_DELAY=-1", "sa", "")) {
            seed(c);
            // Oracle 的 FETCH FIRST 语法在 H2 上同样合法（证明非 MySQL 方言分页可执行）
            String sql = AskdataNl2Sql.withRowLimit("SELECT id FROM t", DatasourceType.ORACLE);
            assertEquals(3, count(c, sql));
        }
    }

    private static void seed(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (id INT)");
            s.execute("INSERT INTO t VALUES (1), (2), (3)");
        }
    }

    private static int count(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = 0;
            while (rs.next()) {
                n++;
            }
            return n;
        }
    }
}
