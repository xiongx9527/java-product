package com.xiongx9527.askdata.ai;

import com.xiongx9527.askdata.datasource.engine.DatasourceConf;
import com.xiongx9527.askdata.datasource.engine.DatasourceType;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

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

    // ---- A6：PG 方言形态正确性（离线，确定性）----

    @Test
    void pgQuoteProducesDoubleQuotedIdentifiers() {
        assertEquals("\"x\"", DatasourceType.quote(DatasourceType.PG, "x"));
        assertEquals("\"id\", \"name\"", DatasourceType.quoteAll(DatasourceType.PG, List.of("id", "name")));
    }

    @Test
    void pgSqlDialectHintUsesLimitNotRownum() {
        String hint = DatasourceType.PG.sqlDialectHint();
        assertTrue(hint.contains("LIMIT"), hint);
        assertFalse(hint.contains("ROWNUM"), hint);
    }

    @Test
    void pgPreviewSqlUsesDoubleQuotesSchemaPrefixAndLimit() {
        DatasourceConf conf = new DatasourceConf();
        conf.setDatabase("pg");
        conf.setDbSchema("public");
        String preview = DatasourceType.PG.previewSql(List.of("id", "name"), "orders", conf, null);
        assertTrue(preview.contains("\"public\".\"orders\""), preview);
        assertTrue(preview.endsWith(" LIMIT 100"), preview);
    }

    /**
     * PG 形态 SQL（双引号标识符 + LIMIT 分页）在 H2 PostgreSQL 兼容模式下确为合法可执行，
     * 离体验证「模型在 PG 下产出的 SQL 形状」能跑通。
     */
    @Test
    void pgDialectSqlExecutesOnH2PostgresqlMode() throws Exception {
        String url = "jdbc:h2:mem:pgmode;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE \"t\" (\"id\" INT, \"name\" VARCHAR(20))");
                s.execute("INSERT INTO \"t\" VALUES (1, 'a'), (2, 'b'), (3, 'c')");
            }
            String base = "SELECT " + DatasourceType.quote(DatasourceType.PG, "id")
                    + " FROM " + DatasourceType.quote(DatasourceType.PG, "t");
            String sql = AskdataNl2Sql.withRowLimit(base, DatasourceType.PG);
            assertTrue(sql.endsWith(" LIMIT 200"), sql);
            assertEquals(3, count(c, sql));
        }
    }
}
